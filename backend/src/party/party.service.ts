import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { DataSource, Repository } from 'typeorm';
import { Party } from './party.entity';
import { Business } from '../business/business.entity';
import { Bill } from '../bills/entities/bill.entity';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { BalanceHistory } from '../ledger/balance-history.entity';
import { CreatePartyDto } from './dto/create-party.dto';
import { UpdatePartyInterestDto } from './dto/update-party-interest.dto';
import { SmsService } from '../notifications/sms/sms.service';

/** Postgres unique_violation. */
const POSTGRES_UNIQUE_VIOLATION = '23505';

@Injectable()
export class PartyService {
  constructor(
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    // Resolvable anywhere: AuthModule is @Global and re-exports TypeOrmModule.
    @InjectRepository(Business)
    private readonly businessRepository: Repository<Business>,
    private readonly smsService: SmsService,
    private readonly dataSource: DataSource,
  ) {}

  findAll(
    businessId: string,
    type?: 'CUSTOMER' | 'SUPPLIER',
  ): Promise<Party[]> {
    const where: any = { businessId };
    if (type) {
      where.type = type;
    }
    return this.partyRepository.find({ where, order: { createdAt: 'DESC' } });
  }

  async findOne(id: string, businessId: string): Promise<Party> {
    const party = await this.partyRepository.findOneBy({ id, businessId });
    if (!party)
      throw new NotFoundException(
        `Party ${id} not found in business ${businessId}`,
      );
    return party;
  }

  async create(businessId: string, dto: CreatePartyDto): Promise<Party> {
    // The client may supply its own id (offline-first). Treat a repeat of an id
    // we already hold as a replayed request and return the stored row unchanged,
    // so retries neither duplicate the party nor re-send the welcome SMS.
    if (dto.id) {
      const existing = await this.findExistingForReplay(dto.id, businessId);
      if (existing) return existing;
    }

    const party = this.partyRepository.create({
      id: dto.id,
      name: dto.name,
      phone: dto.phone,
      type: dto.type,
      gstin: dto.gstin,
      businessId,
      currentBalance: 0,
      interestEnabled: dto.interestEnabled ?? false,
      interestRate: dto.interestEnabled ? dto.interestRate : 0,
      interestPeriodValue: dto.interestEnabled ? dto.interestPeriodValue : 0,
      interestPeriodUnit: dto.interestEnabled
        ? dto.interestPeriodUnit
        : 'DAYS',
    });

    let savedParty: Party;
    try {
      savedParty = await this.partyRepository.save(party);
    } catch (error) {
      // Two concurrent creates of the same id: the loser gets a unique violation.
      if (error?.code === POSTGRES_UNIQUE_VIOLATION && dto.id) {
        const existing = await this.findExistingForReplay(dto.id, businessId);
        if (existing) return existing;
      }
      throw error;
    }

    // Send welcome SMS if phone number is provided
    if (savedParty.phone) {
      // The business name, not its id. Customers previously received
      // "…created by 3f1a9c22-0000-4000-8000-000000000001".
      const business = await this.businessRepository.findOne({
        where: { id: businessId },
      });
      const businessName = business?.name?.trim() || 'your shop';

      await this.smsService.sendSms(
        savedParty.phone,
        `Welcome to ${businessName}! Your Khata Book account has been created.`,
      );
    }

    return savedParty;
  }

  /**
   * Replaces a party's interest configuration.
   *
   * Naturally idempotent: this assigns absolute values rather than applying a
   * delta, so a retried send from the offline Outbox converges on exactly the
   * same row. Touching only the four interest columns leaves currentBalance —
   * and therefore every ledger invariant — untouched by construction; interest
   * only becomes money when a ledger entry is created for it, which is a
   * separate, explicit operation.
   *
   * Disabling interest zeroes the other three fields rather than preserving
   * them, so a party that is off can never carry a stale rate that a later
   * bug could read as live.
   */
  async updateInterest(
    id: string,
    businessId: string,
    dto: UpdatePartyInterestDto,
  ): Promise<Party> {
    // Scoped find: a party in another business 404s rather than being edited.
    const party = await this.findOne(id, businessId);

    party.interestEnabled = dto.interestEnabled;
    party.interestRate = dto.interestEnabled ? (dto.interestRate ?? 0) : 0;
    party.interestPeriodValue = dto.interestEnabled
      ? (dto.interestPeriodValue ?? 0)
      : 0;
    party.interestPeriodUnit = dto.interestEnabled
      ? (dto.interestPeriodUnit ?? 'DAYS')
      : 'DAYS';

    return this.partyRepository.save(party);
  }

  /**
   * Resolves a client-supplied id to an existing row for idempotent replay.
   *
   * Returns the row when it already belongs to this business. If the id exists
   * under a *different* business we reject with a bare 409 — echoing anything
   * about that row would turn client-chosen ids into a cross-tenant existence
   * oracle. Returns null when the id is genuinely new.
   */
  private async findExistingForReplay(
    id: string,
    businessId: string,
  ): Promise<Party | null> {
    const existing = await this.partyRepository.findOneBy({ id });
    if (!existing) return null;
    if (existing.businessId !== businessId) {
      throw new ConflictException('Party id is already in use');
    }
    return existing;
  }

  /**
   * Removes a party and the records that depend on it.
   *
   * Every dependent foreign key is ON DELETE NO ACTION, so removing a party
   * that had any activity previously failed with a constraint violation and a
   * 500 — the delete only ever worked for a party with no transactions.
   *
   * Dependents are removed in foreign key order inside one transaction, via the
   * entity manager rather than raw SQL so the audit subscriber records each
   * removed ledger entry and bill. Bills are included deliberately: an invoice
   * for a party that no longer exists cannot be rendered or reconciled.
   *
   * Concurrency invariant: this acquires the same Party pessimistic_write
   * lock every LedgerService method acquires before touching that party, and
   * acquires it before reading any dependent record — not after, like the
   * cascade below used to. Without the lock, a concurrent
   * LedgerService.create()/update()/delete() for this party could commit a
   * new or changed LedgerEntry in the gap between this method's dependent
   * lookups and its final `manager.remove(party)`, which then failed with an
   * unhandled foreign_key_violation (ledger_entries.party_id is ON DELETE NO
   * ACTION) instead of a clean result. With the lock held first, whichever
   * of this deletion or a concurrent ledger write reaches the row first runs
   * to completion before the other is even allowed to read: if this deletion
   * wins, the concurrent write later sees no party and fails with a normal
   * 404; if the ledger write wins, this deletion's dependent lookups run
   * only after it commits and so already include whatever it just changed.
   */
  async delete(id: string, businessId: string): Promise<void> {
    // Fast existence check before ever opening a transaction, for a plain
    // 404 on a bogus id. The authoritative check is the locked re-fetch
    // below — a party removed between the two calls is handled there.
    await this.findOne(id, businessId);

    await this.dataSource.transaction(async (manager) => {
      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', {
          id,
          businessId,
        })
        .getOne();

      if (!party) {
        // Removed by a concurrent request while we waited for the lock.
        throw new NotFoundException(
          `Party ${id} not found in business ${businessId}`,
        );
      }

      const bills = await manager.find(Bill, {
        where: { partyId: id, businessId },
      });
      if (bills.length) await manager.remove(bills);

      // Detach history from the entries first; it also references the party, so
      // it is removed immediately afterwards.
      await manager.delete(BalanceHistory, { partyId: id, businessId });

      const entries = await manager.find(LedgerEntry, {
        where: { partyId: id, businessId },
      });
      if (entries.length) await manager.remove(entries);

      await manager.remove(party);
    });
  }

  /** Called by LedgerService to atomically update the running balance. We shouldn't use findOne + update to avoid race conditions. */
  async updateBalance(
    partyId: string,
    businessId: string,
    delta: number,
  ): Promise<void> {
    // A better approach for atomicity is using QueryBuilder increment/decrement, but since we use transactions in Ledger, we might just pass the transaction entity manager.
    // However, for MVP, we'll use a direct update with increment.
    await this.partyRepository.increment(
      { id: partyId, businessId },
      'currentBalance',
      delta,
    );
  }
}
