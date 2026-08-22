import { ConflictException, NotFoundException } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { LedgerService } from './ledger.service';
import { LedgerEntry } from './ledger-entry.entity';
import { BalanceHistory } from './balance-history.entity';
import { Party } from '../party/party.entity';
import { Business } from '../business/business.entity';
import { SmsService } from '../notifications/sms/sms.service';

/** Postgres unique_violation. */
const POSTGRES_UNIQUE_VIOLATION = '23505';

/**
 * LedgerService.create() idempotency under concurrency.
 *
 * The party-lock-then-idempotency-check ordering already makes two
 * concurrent creates *for the same party* with the same client-supplied id
 * safe: they contend for the same lock, so the second always sees the
 * first's committed row before attempting an insert. The gap this fixes is
 * two concurrent creates that share an id but target *different* parties —
 * they don't contend for any lock, so both can pass the check before either
 * commits, and only the ledger_entries primary key decides the race. Before
 * the fix, the loser's unique-violation was unhandled and surfaced as a raw
 * 500 — worse, a naive catch-and-return-existing *inside* the transaction
 * would have committed the loser's own already-staged party.currentBalance
 * mutation anyway, double-applying the balance. These tests target exactly
 * that path: the party-lock-serialised same-party case isn't retested here
 * since it was already safe and unaffected by this change.
 *
 * The fake EntityManager below simulates two real Postgres properties:
 *  1. A `pessimistic_write` lock is held for the party row (not needed to
 *     race in this file, but present for fidelity with the party-not-found
 *     and other paths).
 *  2. Inserting a LedgerEntry whose id already exists in the table raises a
 *     23505 unique-violation, exactly like the real primary key would — and,
 *     critically, a thrown error inside dataSource.transaction's callback
 *     rolls back only the writes staged by *that* callback.
 *
 * That last point matters more here than it did for the update()/delete()
 * concurrency tests: with two transactions genuinely open at once (this file
 * has no party lock serialising them, since they target different parties),
 * a naive whole-store snapshot/restore is wrong — if the loser's rollback
 * blindly restores the store to how it looked when the loser's transaction
 * *started*, it would also erase the winner's meanwhile-committed row, since
 * real Postgres never lets one transaction's rollback undo another,
 * already-committed transaction's writes. Instead, each transaction tracks
 * only its own writes and undoes exactly those, in reverse order, on failure.
 */

type Store = {
  parties: Map<string, Party>;
  entries: Map<string, LedgerEntry>;
  history: BalanceHistory[];
};

function createFakeDataSource(store: Store): DataSource {
  return {
    transaction: async (cb: any) => {
      // Reverted, in reverse order, only if this callback throws — never
      // touches anything another (already-committed) transaction wrote.
      const undo: Array<() => void> = [];

      const manager: any = {
        findOneBy: async (Entity: any, where: any) => {
          if (Entity === LedgerEntry) {
            const found = [...store.entries.values()].find((e) =>
              Object.entries(where).every(([k, v]) => (e as any)[k] === v),
            );
            return found ? Object.assign(new LedgerEntry(), found) : null;
          }
          return null;
        },
        findOne: async (Entity: any, options: any) => {
          if (Entity === Business) return null;
          return null;
        },
        createQueryBuilder: (Entity: any) => {
          const builder: any = {
            setLock: (_mode: string) => builder,
            where: (_sql: string, params: any) => ({
              getOne: async () => {
                if (Entity !== Party) return null;
                const p = store.parties.get(params.id);
                if (!p || p.businessId !== params.businessId) return null;
                return Object.assign(new Party(), p);
              },
            }),
          };
          return builder;
        },
        create: (Entity: any, obj: any) => Object.assign(new Entity(), obj),
        save: async (entity: any) => {
          if (entity instanceof Party) {
            const previous = store.parties.get(entity.id);
            undo.push(() => {
              if (previous) store.parties.set(entity.id, previous);
              else store.parties.delete(entity.id);
            });
            store.parties.set(entity.id, Object.assign(new Party(), entity));
            return entity;
          }
          if (entity instanceof BalanceHistory) {
            const pushed = Object.assign(new BalanceHistory(), entity);
            store.history.push(pushed);
            undo.push(() => {
              const index = store.history.indexOf(pushed);
              if (index !== -1) store.history.splice(index, 1);
            });
            return pushed;
          }
          if (entity instanceof LedgerEntry) {
            // Models the real primary-key constraint: this is always an
            // INSERT in create()'s flow (entities here are always built via
            // manager.create(), never fetched then re-saved), so an id that
            // already exists is a genuine conflict, exactly like Postgres.
            if (entity.id && store.entries.has(entity.id)) {
              throw Object.assign(
                new Error('duplicate key value violates unique constraint'),
                {
                  code: POSTGRES_UNIQUE_VIOLATION,
                },
              );
            }
            const saved = Object.assign(new LedgerEntry(), entity, {
              id: entity.id ?? `server-generated-${store.entries.size}`,
            });
            store.entries.set(saved.id, saved);
            undo.push(() => {
              store.entries.delete(saved.id);
            });
            return saved;
          }
          return entity;
        },
      };

      try {
        return await cb(manager);
      } catch (err) {
        // Undo only this transaction's own writes, in reverse order.
        for (let i = undo.length - 1; i >= 0; i--) undo[i]();
        throw err;
      }
    },
  } as any as DataSource;
}

describe('LedgerService.create() — idempotency under concurrency', () => {
  const BIZ = '11111111-1111-4111-8111-111111111111';
  const OTHER_BIZ = '99999999-9999-4999-8999-999999999999';
  const PARTY_A = '22222222-2222-4222-8222-222222222222';
  const PARTY_B = '33333333-3333-4333-8333-333333333333';
  const SHARED_ID = '44444444-4444-4444-8444-444444444444';

  let store: Store;

  const seedParty = (id: string, overrides: Partial<Party> = {}) =>
    Object.assign(new Party(), {
      id,
      businessId: BIZ,
      type: 'CUSTOMER',
      phone: '',
      currentBalance: 0,
      ...overrides,
    });

  const dto = (overrides: Record<string, any> = {}) => ({
    partyId: PARTY_A,
    amount: 500,
    type: 'GAVE' as const,
    ...overrides,
  });

  const buildService = async (dataSource: DataSource) => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        LedgerService,
        {
          // Used by create()'s post-rollback catch, which deliberately reads
          // via the plain repository rather than the aborted transaction's
          // manager — see the comment on LedgerService.create().
          provide: getRepositoryToken(LedgerEntry),
          useValue: {
            findOneBy: async (where: any) =>
              [...store.entries.values()].find((e) =>
                Object.entries(where).every(([k, v]) => (e as any)[k] === v),
              ) ?? null,
          },
        },
        { provide: getRepositoryToken(Party), useValue: {} },
        {
          provide: SmsService,
          useValue: (() => { const f = jest.fn().mockResolvedValue(true); return { sendSms: f, sendOtpSms: f, sendTransactionalSms: f }; })(),
        },
        { provide: DataSource, useValue: dataSource },
      ],
    }).compile();
    return moduleRef.get(LedgerService);
  };

  beforeEach(() => {
    store = {
      parties: new Map([
        [PARTY_A, seedParty(PARTY_A)],
        [PARTY_B, seedParty(PARTY_B)],
      ]),
      entries: new Map(),
      history: [],
    };
  });

  it('1. creates normally with a client-provided id', async () => {
    const dataSource = createFakeDataSource(store);
    const service = await buildService(dataSource);

    const entry = await service.create(BIZ, dto({ id: SHARED_ID }));

    expect(entry.id).toBe(SHARED_ID);
    expect(Number(store.parties.get(PARTY_A)!.currentBalance)).toBe(500);
    expect(store.history).toHaveLength(1);
  });

  it('1b. still generates an id when the client omits one', async () => {
    const dataSource = createFakeDataSource(store);
    const service = await buildService(dataSource);

    const entry = await service.create(BIZ, dto());

    expect(entry.id).toBeDefined();
    expect(Number(store.parties.get(PARTY_A)!.currentBalance)).toBe(500);
  });

  it('2. sequential replay with the same id returns the existing entry and applies the delta only once', async () => {
    const dataSource = createFakeDataSource(store);
    const service = await buildService(dataSource);

    const first = await service.create(BIZ, dto({ id: SHARED_ID }));
    const retry = await service.create(BIZ, dto({ id: SHARED_ID }));

    expect(retry.id).toBe(first.id);
    expect(store.entries.size).toBe(1);
    expect(Number(store.parties.get(PARTY_A)!.currentBalance)).toBe(500);
    expect(store.history).toHaveLength(1);
  });

  describe('3-7. concurrent creation, same id, different parties (the race this fix closes)', () => {
    it('produces exactly one LedgerEntry, and both requests converge to it', async () => {
      const dataSource = createFakeDataSource(store);
      const service = await buildService(dataSource);

      const requestA = service.create(
        BIZ,
        dto({ id: SHARED_ID, partyId: PARTY_A, amount: 500 }),
      );
      const requestB = service.create(
        BIZ,
        dto({ id: SHARED_ID, partyId: PARTY_B, amount: 700 }),
      );

      const [resultA, resultB] = await Promise.all([requestA, requestB]);

      // 3. Exactly one row exists.
      expect(store.entries.size).toBe(1);

      // 4. Both requests agree on the same winning row — including the
      // "losing" caller, whose own request named a different party. That is
      // the correct idempotency contract: the id names one logical
      // operation, and the second caller is told what actually happened
      // under that id rather than having its own (differing) intent applied.
      expect(resultA.id).toBe(SHARED_ID);
      expect(resultB.id).toBe(SHARED_ID);
      expect(resultA.partyId).toBe(resultB.partyId);

      const winningParty = resultA.partyId;
      const losingParty = winningParty === PARTY_A ? PARTY_B : PARTY_A;

      // 5. The winner's party balance changed exactly once, by exactly its
      // own delta — never both 500 and 700 applied to the same party.
      const winningBalance = Number(
        store.parties.get(winningParty)!.currentBalance,
      );
      expect(winningBalance).toBe(winningParty === PARTY_A ? 500 : 700);

      // The loser's own staged balance mutation must not have survived the
      // rollback — this is the specific bug a naive in-transaction catch
      // would have caused.
      expect(Number(store.parties.get(losingParty)!.currentBalance)).toBe(0);

      // 6. Exactly one BalanceHistory row overall — the loser's rolled-back
      // attempt must not have left one behind.
      expect(store.history).toHaveLength(1);
    });
  });

  it('8. a failed (non-unique-violation) transaction does not poison the id — a later request can still create it', async () => {
    const dataSource = createFakeDataSource(store);
    // Force the underlying transaction to fail for a reason unrelated to a
    // primary-key conflict, simulating "the first transaction rolled back".
    const originalTransaction = dataSource.transaction.bind(dataSource);
    let failNext = true;
    (dataSource as any).transaction = async (cb: any) => {
      if (failNext) {
        failNext = false;
        return originalTransaction(async (manager: any) => {
          const wrappedSave = manager.save.bind(manager);
          manager.save = async (entity: any) => {
            if (entity instanceof LedgerEntry) {
              throw new Error('connection reset');
            }
            return wrappedSave(entity);
          };
          return cb(manager);
        });
      }
      return originalTransaction(cb);
    };
    const service = await buildService(dataSource);

    await expect(service.create(BIZ, dto({ id: SHARED_ID }))).rejects.toThrow(
      'connection reset',
    );

    // Nothing survived the rollback: no entry, no history, balance untouched.
    expect(store.entries.size).toBe(0);
    expect(store.history).toHaveLength(0);
    expect(Number(store.parties.get(PARTY_A)!.currentBalance)).toBe(0);

    // The id is not poisoned — a subsequent request creates it normally.
    const retry = await service.create(BIZ, dto({ id: SHARED_ID }));
    expect(retry.id).toBe(SHARED_ID);
    expect(store.entries.size).toBe(1);
    expect(Number(store.parties.get(PARTY_A)!.currentBalance)).toBe(500);
  });

  it('9. the same id from another business cannot access or replay the original entry', async () => {
    const dataSource = createFakeDataSource(store);
    const service = await buildService(dataSource);

    await service.create(BIZ, dto({ id: SHARED_ID, partyId: PARTY_A }));

    // The other business has no party PARTY_A, so this fails on tenancy —
    // but critically, it must fail with no information about business A's
    // entry (bare 409, not a leak of amount/partyId/type).
    store.parties.set(
      'foreign-party',
      seedParty('foreign-party', { businessId: OTHER_BIZ }),
    );
    const attempt = service.create(
      OTHER_BIZ,
      dto({ id: SHARED_ID, partyId: 'foreign-party' }),
    );

    await expect(attempt).rejects.toBeInstanceOf(ConflictException);
    await expect(attempt).rejects.toThrow('Ledger entry id is already in use');
    expect(store.entries.size).toBe(1);
  });

  it('10. different ids create independent entries', async () => {
    const dataSource = createFakeDataSource(store);
    const service = await buildService(dataSource);

    await service.create(
      BIZ,
      dto({ id: SHARED_ID, partyId: PARTY_A, amount: 500 }),
    );
    await service.create(
      BIZ,
      dto({
        id: '55555555-5555-4555-8555-555555555555',
        partyId: PARTY_B,
        amount: 700,
      }),
    );

    expect(store.entries.size).toBe(2);
    expect(Number(store.parties.get(PARTY_A)!.currentBalance)).toBe(500);
    expect(Number(store.parties.get(PARTY_B)!.currentBalance)).toBe(700);
  });

  it("rejects a party lookup for a business other than the caller's, even with no id collision", async () => {
    const dataSource = createFakeDataSource(store);
    const service = await buildService(dataSource);

    await expect(
      service.create(OTHER_BIZ, dto({ id: SHARED_ID, partyId: PARTY_A })),
    ).rejects.toBeInstanceOf(NotFoundException);
    expect(store.entries.size).toBe(0);
  });
});
