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

/** Postgres unique_violation. */
const POSTGRES_UNIQUE_VIOLATION = '23505';

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
 * Rounds a computed balance to exactly 2 decimal places before it is
 * assigned to Party.currentBalance or a BalanceHistory row.
 *
 * Every call site below already produces a mathematically exact 2-decimal
 * result today: each does exactly one addition or subtraction between a
 * value just re-read fresh from the numeric(14,2) column (already exact) and
 * a DTO-validated (<=2 decimal place) input, and IEEE-754 double-precision
 * error at that scale is far below the 0.005 threshold that would change a
 * rounded 2-decimal outcome — verified exhaustively, not assumed. Without
 * this, though, that correctness is *implicit*: it depends on Postgres
 * rounding the value on write, and on nothing ever chaining a second
 * in-memory operation onto a computed balance before it is persisted. This
 * makes the guarantee explicit in the code that computes the value, so it
 * holds regardless of what the database or driver do, and regardless of how
 * this arithmetic is extended later.
 */
function roundMoney(value: number): number {
  return Number(value.toFixed(2));
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

  /**
   * Concurrency invariant for the id-collision case this method still can't
   * rule out via the lock alone: two concurrent creates sharing the same
   * client-supplied id but targeting *different* parties never contend for
   * the same party lock, so both can pass the in-lock idempotency check
   * before either commits. The database's primary-key constraint is what
   * actually decides the race; this method's job is to react to that
   * decision correctly, not to prevent it.
   *
   * The catch below sits *outside* dataSource.transaction, not inside it.
   * That placement is load-bearing: by the time we reach the entry insert,
   * this transaction has already staged its own party.currentBalance
   * mutation. If a unique-violation were caught inside the callback and
   * papered over with `return existing`, the callback would resolve
   * normally and TypeORM would commit — including the loser's own staged
   * balance change, double-applying the delta despite "gracefully"
   * resolving to the winner's row. Letting the error propagate out of the
   * callback instead makes TypeORM roll back the whole transaction, so
   * nothing the loser staged survives. Only then do we re-query for the
   * winner's row, using the plain repository rather than the now-aborted
   * transaction's manager.
   */
  async create(
    businessId: string,
    dto: CreateLedgerEntryDto,
  ): Promise<LedgerEntry> {
    try {
      return await this.dataSource.transaction(async (manager) => {
        // 1. Lock the party row to prevent race conditions during balance update
        const party = await manager
          .createQueryBuilder(Party, 'party')
          .setLock('pessimistic_write')
          .where('party.id = :id AND party.business_id = :businessId', {
            id: dto.partyId,
            businessId,
          })
          .getOne();

        if (!party) {
          throw new NotFoundException(
            `Party ${dto.partyId} not found in business`,
          );
        }

        // 1a. Idempotent replay. The client may supply its own id, so a retried
        // write must return the stored entry and leave the balance alone —
        // re-applying the delta here would double-count money. This runs while
        // holding the party lock, so concurrent retries against the *same*
        // party always serialise behind it and are resolved here already; see
        // the catch below for the cross-party id-collision this cannot catch.
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

        const newBalance = roundMoney(previousBalance + delta);

        // 3. Update party balance
        party.currentBalance = newBalance;
        await manager.save(party);

        // 4. Create Ledger Entry. No try/catch here: a unique-violation must
        // propagate so the whole transaction — including the party save
        // above — rolls back. See the class-level comment on this method.
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

        // 6. Send SMS Notification — unless the client's own "Send
        // transaction SMS" toggle was off when this entry was created.
        // dto.notifyCustomer is only ever explicitly `false`; absent (older
        // clients) or `true` both mean "send", preserving this method's
        // previous unconditional behavior for every existing app build.
        if (party.phone && dto.notifyCustomer !== false) {
          // The business name, not its id. This previously interpolated the raw
          // businessId, so customers received "…3f1a9c22-0000-4000-… gave you".
          const business = await manager.findOne(Business, {
            where: { id: businessId },
          });
          const businessName = business?.name?.trim() || 'Your shop';

          const actionText =
            dto.type === 'GAVE' ? 'gave you' : 'received from you';
          const message =
            `Khata Book: ${businessName} ${actionText} Rs ${dto.amount.toFixed(2)}. ` +
            `${describeBalance(party.type, newBalance)}`;

          // Fire and forget (don't block the transaction). Transactional
          // channel, never TextBee — TextBee is reserved for OTP, and a
          // customer notification must not consume the login allowance.
          this.smsService
            .sendTransactionalSms(party.phone, message)
            .catch(console.error);
        }

        return savedEntry;
      });
    } catch (error) {
      // The transaction above has already been fully rolled back by the time
      // we get here, so nothing this attempt staged (including any party
      // balance mutation) survived. Resolve to whichever transaction won the
      // race with a fresh, non-transactional read.
      if (error?.code === POSTGRES_UNIQUE_VIOLATION && dto.id) {
        const existing = await this.ledgerRepository.findOneBy({ id: dto.id });
        if (existing) {
          if (existing.businessId !== businessId) {
            throw new ConflictException('Ledger entry id is already in use');
          }
          return existing;
        }
      }
      throw error;
    }
  }

  /**
   * Edits an existing entry's amount and/or note.
   *
   * The balance is adjusted by the difference between the new and old deltas,
   * inside the same party lock the other writes use. That difference is what
   * makes this naturally idempotent: replaying the same edit computes an
   * adjustment of zero, because the stored amount already equals the new one.
   *
   * Concurrency invariant: every writer of a LedgerEntry's amount, type or
   * existence (create/update/delete, all in this class) acquires this same
   * Party row's pessimistic_write lock before touching the entry. So once we
   * hold the lock, no other transaction can commit a change to this entry
   * until we release it — which means the entry must be *re-read after the
   * lock is acquired*, not trusted from any read taken beforehand. The
   * initial read below exists only to discover which party to lock (partyId
   * is never editable, so a stale copy of it is harmless); the balance math
   * and the final save always use the post-lock re-fetch.
   */
  async update(
    entryId: string,
    businessId: string,
    dto: UpdateLedgerEntryDto,
  ): Promise<LedgerEntry> {
    return this.dataSource.transaction(async (manager) => {
      // Routing read only — used to find the party to lock. Never used for
      // balance math; see the re-fetch below.
      const initialEntry = await manager.findOne(LedgerEntry, {
        where: { id: entryId, businessId },
      });
      if (!initialEntry) {
        throw new NotFoundException(`LedgerEntry ${entryId} not found`);
      }

      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', {
          id: initialEntry.partyId,
          businessId,
        })
        .getOne();

      if (!party) {
        throw new NotFoundException(
          `Party ${initialEntry.partyId} not found in business`,
        );
      }

      // Fresh copy, taken only now that the party lock is held. A concurrent
      // update or delete that committed while we waited for the lock is
      // reflected here — using initialEntry past this point would silently
      // reapply a stale amount on top of already-applied changes.
      const entry = await manager.findOne(LedgerEntry, {
        where: { id: entryId, businessId },
      });
      if (!entry) {
        // Deleted by a concurrent request while we waited for the lock.
        throw new NotFoundException(`LedgerEntry ${entryId} not found`);
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
          const newBalance = roundMoney(previousBalance + adjustment);
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

  /**
   * Concurrency invariant: identical to update() above — the party lock
   * serialises every writer of this entry, so the entry itself must be
   * re-read after the lock is acquired. Here, a null re-fetch means a
   * concurrent request already deleted this entry (and already reversed its
   * balance effect), so this call converges to a no-op success rather than
   * reversing the balance a second time against a stale in-memory copy.
   */
  async delete(entryId: string, businessId: string): Promise<void> {
    return this.dataSource.transaction(async (manager) => {
      // Routing read only — used to find the party to lock. See update().
      const initialEntry = await manager.findOne(LedgerEntry, {
        where: { id: entryId, businessId },
      });
      if (!initialEntry)
        throw new NotFoundException(`LedgerEntry ${entryId} not found`);

      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', {
          id: initialEntry.partyId,
          businessId,
        })
        .getOne();

      // Fresh copy, taken only now that the party lock is held.
      const entry = await manager.findOne(LedgerEntry, {
        where: { id: entryId, businessId },
      });
      if (!entry) {
        // Already deleted by a concurrent request — that is exactly the
        // outcome this call wants, so it succeeds without reversing the
        // balance a second time.
        return;
      }

      if (party) {
        const previousBalance = Number(party.currentBalance);

        // Removing an entry undoes exactly the delta it applied.
        const reverseDelta = -balanceDelta(
          party.type,
          entry.type,
          entry.amount,
        );

        const newBalance = roundMoney(previousBalance + reverseDelta);

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

      // Release the history rows written when this entry was created. Their
      // foreign key is ON DELETE NO ACTION, so leaving them pointed at the row
      // makes the delete below fail outright — every delete returned 500.
      // Detaching rather than deleting keeps the balance trail intact.
      await manager.update(
        BalanceHistory,
        { ledgerEntryId: entry.id },
        { ledgerEntryId: null },
      );

      await manager.remove(entry);
    });
  }
}
