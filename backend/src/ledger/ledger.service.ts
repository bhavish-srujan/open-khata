import {
  BadRequestException,
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import {
  Repository,
  DataSource,
  Between,
  MoreThanOrEqual,
  LessThanOrEqual,
} from 'typeorm';
import { LedgerEntry } from './ledger-entry.entity';
import { Party } from '../party/party.entity';
import { Business } from '../business/business.entity';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';
import { UpdateLedgerEntryDto } from './dto/update-ledger-entry.dto';
import { BalanceHistory } from './balance-history.entity';
import { SmsService } from '../notifications/sms/sms.service';

/** Upper bound on the business-wide feed, so one call cannot pull a whole ledger. */
const MAX_LEDGER_FEED_ROWS = 500;

/**
 * Signed effect of an entry on its party's running balance.
 *
 * CUSTOMER: GAVE means credit extended to them, so they owe us more (+);
 * GOT means they paid us (-). SUPPLIER inverts both, which is why a positive
 * supplier balance means we owe them.
 *
 * Extracted so create, update and delete cannot drift apart — the reminder
 * scheduler and the app's "You will get" labels all depend on this convention.
 */
function balanceDelta(
  partyType: Party['type'],
  entryType: 'GAVE' | 'GOT',
  amount: number,
): number {
  const magnitude = Number(amount);
  if (partyType === 'CUSTOMER') {
    return entryType === 'GAVE' ? magnitude : -magnitude;
  }
  return entryType === 'GAVE' ? -magnitude : magnitude;
}

/**
 * States a balance from the recipient's point of view.
 *
 * The previous message sent `Math.abs(balance)` under the fixed label "Current
 * Balance", which told a customer holding credit that they owed that amount —
 * a factual error in a message about money, and one that invites paying twice.
 */
function describeBalance(partyType: Party['type'], balance: number): string {
  const amount = Math.abs(balance);
  // Below half a paisa is settled; avoids "Rs 0.00 due" from rounding dust.
  if (amount < 0.005) return 'Your account is now settled.';

  const owesUs = partyType === 'CUSTOMER' ? balance > 0 : balance < 0;
  return owesUs
    ? `Balance due: Rs ${amount.toFixed(2)}.`
    : `You are in credit: Rs ${amount.toFixed(2)}.`;
}

@Injectable()
export class LedgerService {
  constructor(
    @InjectRepository(LedgerEntry)
    private readonly ledgerRepository: Repository<LedgerEntry>,
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    private dataSource: DataSource,
    private smsService: SmsService,
  ) {}

  findByParty(partyId: string, businessId: string): Promise<LedgerEntry[]> {
    return this.ledgerRepository.find({
      where: { partyId, businessId },
      order: { createdAt: 'DESC' },
    });
  }

  /**
   * Business-wide feed, newest first, for the cashbook. Capped rather than
   * paginated: the client renders a recent-activity list, and an unbounded
   * find() on a busy business would be a denial of service against ourselves.
   */
  findByBusiness(
    businessId: string,
    range: { from?: string; to?: string } = {},
  ): Promise<LedgerEntry[]> {
    const where: any = { businessId };

    const from = range.from ? new Date(range.from) : undefined;
    const to = range.to ? new Date(range.to) : undefined;

    if (from && to) where.createdAt = Between(from, to);
    else if (from) where.createdAt = MoreThanOrEqual(from);
    else if (to) where.createdAt = LessThanOrEqual(to);

    return this.ledgerRepository.find({
      where,
      order: { createdAt: 'DESC' },
      take: MAX_LEDGER_FEED_ROWS,
      relations: { party: true },
    });
  }

  async create(businessId: string, dto: CreateLedgerEntryDto): Promise<LedgerEntry> {
    return this.dataSource.transaction(async (manager) => {
      // 1. Lock the party row to prevent race conditions during balance update
      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', { id: dto.partyId, businessId })
        .getOne();

      if (!party) {
        throw new NotFoundException(`Party ${dto.partyId} not found in business`);
      }

      // 1a. Idempotent replay. The client may supply its own id, so a retried
      // write must return the stored entry and leave the balance alone —
      // re-applying the delta here would double-count money. This runs while
      // holding the party lock, so concurrent retries serialise behind it.
      if (dto.id) {
        const existing = await manager.findOneBy(LedgerEntry, { id: dto.id });
        if (existing) {
          if (existing.businessId !== businessId) {
            // Bare 409: never confirm anything about another tenant's row.
            throw new ConflictException('Ledger entry id is already in use');
          }
          return existing;
        }
      }

      const previousBalance = Number(party.currentBalance);

      // 2. Calculate delta
      const delta = balanceDelta(party.type, dto.type, dto.amount);

      const newBalance = previousBalance + delta;

      // 3. Update party balance
      party.currentBalance = newBalance;
      await manager.save(party);

      // 4. Create Ledger Entry
      const entry = manager.create(LedgerEntry, {
        id: dto.id,
        businessId,
        partyId: dto.partyId,
        amount: dto.amount,
        type: dto.type,
        note: dto.note ?? '',
        paymentMethod: dto.paymentMethod ?? 'Cash',
        source: dto.source ?? 'MANUAL',
        status: 'CONFIRMED',
      });
      const savedEntry = await manager.save(entry);

      // 5. Create Balance History
      const history = manager.create(BalanceHistory, {
        businessId,
        partyId: dto.partyId,
        ledgerEntryId: savedEntry.id,
        previousBalance,
        newBalance,
      });
      await manager.save(history);

      // 6. Send SMS Notification
      if (party.phone) {
        // The business name, not its id. This previously interpolated the raw
        // businessId, so customers received "…3f1a9c22-0000-4000-… gave you".
        const business = await manager.findOne(Business, {
          where: { id: businessId },
        });
        const businessName = business?.name?.trim() || 'Your shop';

        const actionText = dto.type === 'GAVE' ? 'gave you' : 'received from you';
        const message =
          `Khata Book: ${businessName} ${actionText} Rs ${dto.amount.toFixed(2)}. ` +
          `${describeBalance(party.type, newBalance)}`;

        // Fire and forget (don't block the transaction)
        this.smsService.sendSms(party.phone, message).catch(console.error);
      }

      return savedEntry;
    });
  }

  /**
   * Edits an existing entry's amount and/or note.
   *
   * The balance is adjusted by the difference between the new and old deltas,
   * inside the same party lock the other writes use. That difference is what
   * makes this naturally idempotent: replaying the same edit computes an
   * adjustment of zero, because the stored amount already equals the new one.
   */
  async update(
    entryId: string,
    businessId: string,
    dto: UpdateLedgerEntryDto,
  ): Promise<LedgerEntry> {
    return this.dataSource.transaction(async (manager) => {
      const entry = await manager.findOne(LedgerEntry, {
        where: { id: entryId, businessId },
      });
      if (!entry) {
        throw new NotFoundException(`LedgerEntry ${entryId} not found`);
      }

      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', {
          id: entry.partyId,
          businessId,
        })
        .getOne();

      if (!party) {
        throw new NotFoundException(`Party ${entry.partyId} not found in business`);
      }

      if (dto.note !== undefined) {
        entry.note = dto.note;
      }

      if (dto.amount !== undefined) {
        const previousBalance = Number(party.currentBalance);
        const adjustment =
          balanceDelta(party.type, entry.type, dto.amount) -
          balanceDelta(party.type, entry.type, entry.amount);

        if (adjustment !== 0) {
          const newBalance = previousBalance + adjustment;
          party.currentBalance = newBalance;
          await manager.save(party);

          await manager.save(
            manager.create(BalanceHistory, {
              businessId,
              partyId: entry.partyId,
              ledgerEntryId: entry.id,
              previousBalance,
              newBalance,
            }),
          );
        }

        entry.amount = dto.amount;
      }

      return manager.save(entry);
    });
  }

  async delete(entryId: string, businessId: string): Promise<void> {
    return this.dataSource.transaction(async (manager) => {
      const entry = await manager.findOne(LedgerEntry, { where: { id: entryId, businessId } });
      if (!entry) throw new NotFoundException(`LedgerEntry ${entryId} not found`);

      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', { id: entry.partyId, businessId })
        .getOne();

      if (party) {
        const previousBalance = Number(party.currentBalance);
        
        // Removing an entry undoes exactly the delta it applied.
        const reverseDelta = -balanceDelta(party.type, entry.type, entry.amount);

        const newBalance = previousBalance + reverseDelta;

        party.currentBalance = newBalance;
        await manager.save(party);

        // 5. Create Balance History for the deletion
        const history = manager.create(BalanceHistory, {
          businessId,
          partyId: entry.partyId,
          ledgerEntryId: null, // Because the entry is being deleted
          previousBalance,
          newBalance,
        });
        await manager.save(history);
      }

      await manager.remove(entry);
    });
  }
}
