import { NotFoundException } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { LedgerService } from './ledger.service';
import { LedgerEntry } from './ledger-entry.entity';
import { BalanceHistory } from './balance-history.entity';
import { Party } from '../party/party.entity';
import { Business } from '../business/business.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * Proves the update()/delete() race fix with genuinely concurrent execution,
 * not just sequential calls that happen to look like a race.
 *
 * The fake EntityManager below models the two Postgres properties the fix
 * depends on:
 *
 *  1. A `pessimistic_write` lock on a Party row is held for the lifetime of
 *     the transaction (released only when the callback settles), not just
 *     for the duration of the SELECT — a second transaction locking the same
 *     party genuinely blocks until the first commits or rolls back.
 *  2. Every read reflects the latest *committed* state at the moment it
 *     runs (READ COMMITTED) — a read taken before a lock is acquired can be
 *     stale by the time the lock is granted; a read taken after is not.
 *
 * A "routing gate" forces both sides of a race to take their pre-lock,
 * stale LedgerEntry snapshot before either is allowed to proceed to lock
 * acquisition — otherwise a fast, purely-sequential Promise chain could
 * "pass" a test that would fail under real concurrency.
 */

type Store = {
  parties: Map<string, Party>;
  entries: Map<string, LedgerEntry>;
  history: BalanceHistory[];
};

function cloneStore(store: Store): Store {
  return {
    parties: new Map(
      [...store.parties].map(([k, v]) => [k, Object.assign(new Party(), v)]),
    ),
    entries: new Map(
      [...store.entries].map(([k, v]) => [
        k,
        Object.assign(new LedgerEntry(), v),
      ]),
    ),
    history: store.history.map((h) => Object.assign(new BalanceHistory(), h)),
  };
}

function restoreStore(target: Store, snapshot: Store) {
  target.parties = snapshot.parties;
  target.entries = snapshot.entries;
  target.history = snapshot.history;
}

/** Chains pessimistic_write lock acquisition per party id, held until release() is called. */
function createPartyLockManager() {
  const chains = new Map<string, Promise<any>>();
  return {
    acquire(partyId: string): { ready: Promise<void>; release: () => void } {
      const prior = chains.get(partyId) ?? Promise.resolve();
      let release!: () => void;
      const mine = new Promise<void>((resolve) => {
        release = resolve;
      });
      chains.set(
        partyId,
        prior.then(() => mine),
      );
      return { ready: prior, release };
    },
  };
}

/** Releases every awaiting arrive() call only once `expected` callers have arrived. */
function createRoutingGate(expected: number) {
  let arrived = 0;
  let resolveAll!: () => void;
  const allArrived = new Promise<void>((resolve) => {
    resolveAll = resolve;
  });
  return {
    async arrive() {
      arrived += 1;
      if (arrived >= expected) resolveAll();
      await allArrived;
    },
  };
}

function createFakeDataSource(
  store: Store,
  partyLockMgr: ReturnType<typeof createPartyLockManager>,
  routingGate?: ReturnType<typeof createRoutingGate>,
  opts: { failOnSaveCall?: number } = {},
): DataSource {
  return {
    transaction: async (cb: any) => {
      const snapshot = cloneStore(store);
      let routed = false;
      let lockRelease: (() => void) | null = null;
      let saveCallCount = 0;

      const findLedgerEntry = (where: any) =>
        [...store.entries.values()].find((e) =>
          Object.entries(where).every(([k, v]) => (e as any)[k] === v),
        ) ?? null;

      const manager: any = {
        findOne: async (Entity: any, options: any) => {
          if (Entity === LedgerEntry) {
            const isFirstRead = !routed;
            routed = true;
            const found = findLedgerEntry(options.where);
            const snapshotEntry = found
              ? Object.assign(new LedgerEntry(), found)
              : null;
            if (isFirstRead && routingGate) {
              await routingGate.arrive();
            }
            return snapshotEntry;
          }
          if (Entity === Business) return null;
          return null;
        },
        findOneBy: async (Entity: any, where: any) => {
          if (Entity === LedgerEntry) {
            const found = findLedgerEntry(where);
            return found ? Object.assign(new LedgerEntry(), found) : null;
          }
          return null;
        },
        createQueryBuilder: (Entity: any) => {
          let locked = false;
          const builder: any = {
            setLock: (_mode: string) => {
              locked = true;
              return builder;
            },
            where: (_sql: string, params: any) => ({
              getOne: async () => {
                if (Entity !== Party) return null;
                if (locked) {
                  const { ready, release } = partyLockMgr.acquire(params.id);
                  await ready;
                  lockRelease = release;
                }
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
          saveCallCount += 1;
          if (opts.failOnSaveCall && saveCallCount === opts.failOnSaveCall) {
            throw new Error('simulated failure before commit');
          }
          if (entity instanceof Party) {
            store.parties.set(entity.id, Object.assign(new Party(), entity));
          } else if (entity instanceof BalanceHistory) {
            store.history.push(Object.assign(new BalanceHistory(), entity));
          } else if (entity instanceof LedgerEntry) {
            store.entries.set(
              entity.id,
              Object.assign(new LedgerEntry(), entity),
            );
          }
          return entity;
        },
        update: async (Entity: any, criteria: any, partial: any) => {
          if (Entity === BalanceHistory) {
            for (const h of store.history) {
              if (
                Object.entries(criteria).every(([k, v]) => (h as any)[k] === v)
              ) {
                Object.assign(h, partial);
              }
            }
          }
        },
        remove: async (entity: any) => {
          if (entity instanceof LedgerEntry) {
            store.entries.delete(entity.id);
          }
        },
      };

      try {
        return await cb(manager);
      } catch (err) {
        // Models Postgres rolling back every write the callback made.
        restoreStore(store, snapshot);
        throw err;
      } finally {
        lockRelease?.();
      }
    },
  } as any as DataSource;
}

describe('LedgerService — update()/delete() race condition fix', () => {
  const BIZ = '11111111-1111-4111-8111-111111111111';
  const OTHER_BIZ = '99999999-9999-4999-8999-999999999999';
  const PARTY_ID = '22222222-2222-4222-8222-222222222222';
  const ENTRY_ID = '33333333-3333-4333-8333-333333333333';

  let store: Store;
  let partyLockMgr: ReturnType<typeof createPartyLockManager>;

  const seedParty = (overrides: Partial<Party> = {}) =>
    Object.assign(new Party(), {
      id: PARTY_ID,
      businessId: BIZ,
      type: 'CUSTOMER',
      phone: '+919876543210',
      currentBalance: 500,
      ...overrides,
    });

  const seedEntry = (overrides: Partial<LedgerEntry> = {}) =>
    Object.assign(new LedgerEntry(), {
      id: ENTRY_ID,
      businessId: BIZ,
      partyId: PARTY_ID,
      amount: 500,
      type: 'GAVE',
      note: 'original',
      ...overrides,
    });

  const buildService = async (dataSource: DataSource) => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        LedgerService,
        { provide: getRepositoryToken(LedgerEntry), useValue: {} },
        { provide: getRepositoryToken(Party), useValue: {} },
        {
          provide: SmsService,
          useValue: { sendSms: jest.fn().mockResolvedValue(true) },
        },
        { provide: DataSource, useValue: dataSource },
      ],
    }).compile();
    return moduleRef.get(LedgerService);
  };

  beforeEach(() => {
    store = {
      parties: new Map([[PARTY_ID, seedParty()]]),
      entries: new Map([[ENTRY_ID, seedEntry()]]),
      history: [],
    };
    partyLockMgr = createPartyLockManager();
  });

  describe('update / update — concurrent edits of the same entry', () => {
    it('never lets Party.currentBalance become 900 (the pre-fix bug), and stays consistent with the final entry amount', async () => {
      const gate = createRoutingGate(2);
      const dataSource = createFakeDataSource(store, partyLockMgr, gate);
      const service = await buildService(dataSource);

      const requestA = service.update(ENTRY_ID, BIZ, { amount: 800 });
      const requestB = service.update(ENTRY_ID, BIZ, { amount: 600 });

      await Promise.all([requestA, requestB]);

      const finalParty = store.parties.get(PARTY_ID)!;
      const finalEntry = store.entries.get(ENTRY_ID)!;
      const finalBalance = Number(finalParty.currentBalance);
      const finalAmount = Number(finalEntry.amount);

      // The old bug: both requests' balance math ends up applied on top of
      // each other using a stale old-amount, landing on 500 + 300 + 100 = 900.
      expect(finalBalance).not.toBe(900);

      // Whichever request's write lands last decides the entry's amount —
      // acceptable last-write-wins on the field itself.
      expect([600, 800]).toContain(finalAmount);

      // But the balance must always be exactly what that final entry implies:
      // CUSTOMER + GAVE means balance === amount, since this is the party's
      // only entry.
      expect(finalBalance).toBe(finalAmount);
    });
  });

  describe('delete / delete — concurrent deletes of the same entry', () => {
    it('applies exactly one reversal, never reaching -500, and both calls converge to success', async () => {
      const gate = createRoutingGate(2);
      const dataSource = createFakeDataSource(store, partyLockMgr, gate);
      const service = await buildService(dataSource);

      await expect(
        Promise.all([
          service.delete(ENTRY_ID, BIZ),
          service.delete(ENTRY_ID, BIZ),
        ]),
      ).resolves.toBeDefined();

      const finalParty = store.parties.get(PARTY_ID)!;
      expect(Number(finalParty.currentBalance)).toBe(0);
      expect(Number(finalParty.currentBalance)).not.toBe(-500);
      expect(store.entries.has(ENTRY_ID)).toBe(false);
    });
  });

  describe('delete / update — concurrent delete and edit of the same entry', () => {
    it('leaves an internally consistent final state: zero entries, zero balance', async () => {
      const gate = createRoutingGate(2);
      const dataSource = createFakeDataSource(store, partyLockMgr, gate);
      const service = await buildService(dataSource);

      const results = await Promise.allSettled([
        service.delete(ENTRY_ID, BIZ),
        service.update(ENTRY_ID, BIZ, { amount: 800 }),
      ]);

      // One of the two must observe the entry gone once it re-fetches after
      // the lock: delete converges to a no-op, or update reports not-found.
      // Either is an acceptable outcome — what matters is the final state.
      const rejected = results.filter((r) => r.status === 'rejected');
      if (rejected.length > 0) {
        expect(rejected[0].reason).toBeInstanceOf(NotFoundException);
      }

      const finalParty = store.parties.get(PARTY_ID)!;
      const entryStillExists = store.entries.has(ENTRY_ID);

      if (entryStillExists) {
        // The update won: the surviving entry's amount must match the balance.
        const finalEntry = store.entries.get(ENTRY_ID)!;
        expect(Number(finalParty.currentBalance)).toBe(
          Number(finalEntry.amount),
        );
      } else {
        // The delete won (or both converged on deletion): no entries left,
        // so the balance must be exactly zero — never a stray reversal of a
        // stale pre-update amount.
        expect(Number(finalParty.currentBalance)).toBe(0);
      }
    });
  });

  describe('cross-business isolation is preserved by the post-lock re-fetch', () => {
    it('update: refuses an entry belonging to another business without ever locking a foreign party', async () => {
      store.entries.set(ENTRY_ID, seedEntry({ businessId: OTHER_BIZ }));
      const dataSource = createFakeDataSource(store, partyLockMgr);
      const service = await buildService(dataSource);

      await expect(
        service.update(ENTRY_ID, BIZ, { amount: 800 }),
      ).rejects.toBeInstanceOf(NotFoundException);
      // Untouched: the wrong-tenant lookup must fail before any mutation.
      expect(Number(store.parties.get(PARTY_ID)!.currentBalance)).toBe(500);
    });

    it('delete: refuses an entry belonging to another business', async () => {
      store.entries.set(ENTRY_ID, seedEntry({ businessId: OTHER_BIZ }));
      const dataSource = createFakeDataSource(store, partyLockMgr);
      const service = await buildService(dataSource);

      await expect(service.delete(ENTRY_ID, BIZ)).rejects.toBeInstanceOf(
        NotFoundException,
      );
      expect(store.entries.has(ENTRY_ID)).toBe(true);
      expect(Number(store.parties.get(PARTY_ID)!.currentBalance)).toBe(500);
    });

    it('the post-lock re-fetch itself is business-scoped, not just the initial routing read', async () => {
      // A same-id entry exists in BOTH businesses, so a re-fetch that dropped
      // the businessId filter would silently pick up the wrong tenant's row.
      store.entries.set(ENTRY_ID, seedEntry({ businessId: BIZ, amount: 500 }));
      const foreignFindOneCalls: any[] = [];
      const dataSource = createFakeDataSource(store, partyLockMgr);
      const originalTransaction = dataSource.transaction.bind(dataSource);
      (dataSource as any).transaction = async (cb: any) =>
        originalTransaction(async (manager: any) => {
          const originalFindOne = manager.findOne.bind(manager);
          manager.findOne = async (Entity: any, options: any) => {
            if (Entity === LedgerEntry) foreignFindOneCalls.push(options.where);
            return originalFindOne(Entity, options);
          };
          return cb(manager);
        });
      const service = await buildService(dataSource);

      await service.update(ENTRY_ID, BIZ, { amount: 800 });

      // Both the routing read and the post-lock re-fetch must carry businessId.
      expect(foreignFindOneCalls.length).toBeGreaterThanOrEqual(2);
      for (const where of foreignFindOneCalls) {
        expect(where.businessId).toBe(BIZ);
      }
    });
  });

  describe('transaction failure leaves no partial write', () => {
    it('update: a failure on the final save rolls back the party balance change too', async () => {
      // 1st save() call in update() is the Party save; force it to fail after
      // the balance has already been computed and assigned in memory.
      const dataSource = createFakeDataSource(store, partyLockMgr, undefined, {
        failOnSaveCall: 1,
      });
      const service = await buildService(dataSource);

      await expect(
        service.update(ENTRY_ID, BIZ, { amount: 800 }),
      ).rejects.toThrow('simulated failure before commit');

      expect(Number(store.parties.get(PARTY_ID)!.currentBalance)).toBe(500);
      expect(Number(store.entries.get(ENTRY_ID)!.amount)).toBe(500);
      expect(store.history).toHaveLength(0);
    });

    it('delete: a failure before the entry is removed rolls back the reversal', async () => {
      // 1st save() call in delete() is the Party save (the reversal).
      const dataSource = createFakeDataSource(store, partyLockMgr, undefined, {
        failOnSaveCall: 1,
      });
      const service = await buildService(dataSource);

      await expect(service.delete(ENTRY_ID, BIZ)).rejects.toThrow(
        'simulated failure before commit',
      );

      expect(Number(store.parties.get(PARTY_ID)!.currentBalance)).toBe(500);
      expect(store.entries.has(ENTRY_ID)).toBe(true);
      expect(store.history).toHaveLength(0);
    });
  });
});
