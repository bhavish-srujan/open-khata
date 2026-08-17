import { NotFoundException } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { PartyService } from './party.service';
import { LedgerService } from '../ledger/ledger.service';
import { Party } from './party.entity';
import { Business } from '../business/business.entity';
import { Bill } from '../bills/entities/bill.entity';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { BalanceHistory } from '../ledger/balance-history.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * PartyService.delete() vs. a concurrent LedgerService.create() for the same
 * party.
 *
 * Before this fix, delete() read its dependent bills/history/entries with no
 * lock on the party row at all, then removed the party as its last step. A
 * concurrent create() for that party — which always locks the party first —
 * could commit a brand-new LedgerEntry in the gap between delete()'s
 * dependent lookups and its final `manager.remove(party)`. Since
 * ledger_entries.party_id is ON DELETE NO ACTION, that DELETE FROM parties
 * then failed with an unhandled foreign_key_violation, rolling back the
 * whole cascade and surfacing as a raw 500.
 *
 * The fake EntityManager below models the two Postgres properties this fix
 * depends on: a `pessimistic_write` lock is held for the party row for the
 * lifetime of the transaction (not just the SELECT), and a thrown error
 * inside dataSource.transaction's callback rolls back only the writes that
 * *specific* callback staged (per-transaction undo tracking, not a global
 * snapshot — see ledger.service.create-idempotency.spec.ts for why a naive
 * whole-store snapshot is wrong once two transactions are genuinely open at
 * once).
 */

type Store = {
  parties: Map<string, Party>;
  entries: Map<string, LedgerEntry>;
  bills: Map<string, Bill>;
  history: BalanceHistory[];
};

/**
 * Polls `predicate` until it's true, bounded rather than an unbounded loop.
 * Against a party.service.ts that never acquires a party lock at all (the
 * pre-fix state), the "has the lock" signal these tests wait on never
 * fires — an unbounded `while` loop would then spin forever instead of
 * failing. This turns that into a fast, clear assertion failure instead.
 */
async function waitFor(
  predicate: () => boolean,
  description: string,
  maxIterations = 2000,
): Promise<void> {
  for (let i = 0; i < maxIterations; i++) {
    if (predicate()) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error(`Timed out waiting for: ${description}`);
}

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

function createFakeDataSource(
  store: Store,
  partyLockMgr: ReturnType<typeof createPartyLockManager>,
  opts: {
    failOnCall?: { method: 'remove' | 'save'; count: number };
    /** Fired the instant a transaction's party lock is granted — before it
     *  does anything else — so a test can observe exactly when each side of
     *  a race actually acquires the lock, not just when it was called. */
    onPartyLocked?: () => void;
  } = {},
): DataSource {
  return {
    transaction: async (cb: any) => {
      const undo: Array<() => void> = [];
      let lockRelease: (() => void) | null = null;
      let removeCalls = 0;
      let saveCalls = 0;

      const manager: any = {
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
                  opts.onPartyLocked?.();
                }
                const p = store.parties.get(params.id);
                if (!p || p.businessId !== params.businessId) return null;
                return Object.assign(new Party(), p);
              },
            }),
          };
          return builder;
        },
        find: async (Entity: any, options: any) => {
          const where = options?.where ?? {};
          if (Entity === Bill) {
            return [...store.bills.values()].filter((b) =>
              Object.entries(where).every(([k, v]) => (b as any)[k] === v),
            );
          }
          if (Entity === LedgerEntry) {
            return [...store.entries.values()].filter((e) =>
              Object.entries(where).every(([k, v]) => (e as any)[k] === v),
            );
          }
          return [];
        },
        findOne: async (Entity: any, options: any) => {
          if (Entity === Business) return null;
          if (Entity === LedgerEntry) {
            const where = options.where;
            return (
              [...store.entries.values()].find((e) =>
                Object.entries(where).every(([k, v]) => (e as any)[k] === v),
              ) ?? null
            );
          }
          return null;
        },
        findOneBy: async (Entity: any, where: any) => {
          if (Entity === LedgerEntry) {
            return (
              [...store.entries.values()].find((e) => e.id === where.id) ?? null
            );
          }
          return null;
        },
        create: (Entity: any, obj: any) => Object.assign(new Entity(), obj),
        save: async (entity: any) => {
          saveCalls += 1;
          if (
            opts.failOnCall?.method === 'save' &&
            saveCalls === opts.failOnCall.count
          ) {
            throw new Error('simulated failure before commit');
          }
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
              const i = store.history.indexOf(pushed);
              if (i !== -1) store.history.splice(i, 1);
            });
            return pushed;
          }
          if (entity instanceof LedgerEntry) {
            if (entity.id && store.entries.has(entity.id)) {
              throw Object.assign(new Error('duplicate key'), {
                code: '23505',
              });
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
        remove: async (entityOrArray: any) => {
          const list = Array.isArray(entityOrArray)
            ? entityOrArray
            : [entityOrArray];
          for (const entity of list) {
            removeCalls += 1;
            if (
              opts.failOnCall?.method === 'remove' &&
              removeCalls === opts.failOnCall.count
            ) {
              throw new Error('simulated failure before commit');
            }
            if (entity instanceof Party) {
              const previous = store.parties.get(entity.id);
              store.parties.delete(entity.id);
              undo.push(() => {
                if (previous) store.parties.set(entity.id, previous);
              });
            } else if (entity instanceof Bill) {
              const previous = store.bills.get(entity.id);
              store.bills.delete(entity.id);
              undo.push(() => {
                if (previous) store.bills.set(entity.id, previous);
              });
            } else if (entity instanceof LedgerEntry) {
              const previous = store.entries.get(entity.id);
              store.entries.delete(entity.id);
              undo.push(() => {
                if (previous) store.entries.set(entity.id, previous);
              });
            }
          }
        },
        delete: async (Entity: any, criteria: any) => {
          if (Entity === BalanceHistory) {
            const removed: BalanceHistory[] = [];
            store.history = store.history.filter((h) => {
              const match = Object.entries(criteria).every(
                ([k, v]) => (h as any)[k] === v,
              );
              if (match) removed.push(h);
              return !match;
            });
            undo.push(() => {
              store.history.push(...removed);
            });
          }
        },
        update: async () => undefined,
      };

      try {
        return await cb(manager);
      } catch (err) {
        // Undo only this transaction's own writes, in reverse order — never
        // another (already-committed) transaction's writes.
        for (let i = undo.length - 1; i >= 0; i--) undo[i]();
        throw err;
      } finally {
        lockRelease?.();
      }
    },
  } as any as DataSource;
}

describe('PartyService.delete() — party-row lock and its concurrency with LedgerService', () => {
  const BIZ = '11111111-1111-4111-8111-111111111111';
  const OTHER_BIZ = '99999999-9999-4999-8999-999999999999';
  const PARTY_ID = '22222222-2222-4222-8222-222222222222';

  let store: Store;
  let partyLockMgr: ReturnType<typeof createPartyLockManager>;

  const seedParty = (overrides: Partial<Party> = {}) =>
    Object.assign(new Party(), {
      id: PARTY_ID,
      businessId: BIZ,
      type: 'CUSTOMER',
      phone: '',
      currentBalance: 500,
      ...overrides,
    });

  const seedEntry = (id: string, overrides: Partial<LedgerEntry> = {}) =>
    Object.assign(new LedgerEntry(), {
      id,
      businessId: BIZ,
      partyId: PARTY_ID,
      amount: 500,
      type: 'GAVE',
      ...overrides,
    });

  const buildServices = async (dataSource: DataSource) => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        PartyService,
        LedgerService,
        { provide: getRepositoryToken(LedgerEntry), useValue: {} },
        {
          provide: getRepositoryToken(Party),
          useValue: {
            findOneBy: async (where: any) => {
              const p = store.parties.get(where.id);
              return p && p.businessId === where.businessId ? p : null;
            },
          },
        },
        {
          provide: getRepositoryToken(Business),
          useValue: { findOne: async () => null },
        },
        {
          provide: SmsService,
          useValue: { sendSms: jest.fn().mockResolvedValue(true) },
        },
        { provide: DataSource, useValue: dataSource },
      ],
    }).compile();
    return {
      partyService: moduleRef.get(PartyService),
      ledgerService: moduleRef.get(LedgerService),
    };
  };

  beforeEach(() => {
    store = {
      parties: new Map(),
      entries: new Map(),
      bills: new Map(),
      history: [],
    };
    partyLockMgr = createPartyLockManager();
  });

  it('1. normal party deletion succeeds and removes the party', async () => {
    store.parties.set(PARTY_ID, seedParty());
    const { partyService } = await buildServices(
      createFakeDataSource(store, partyLockMgr),
    );

    await partyService.delete(PARTY_ID, BIZ);

    expect(store.parties.has(PARTY_ID)).toBe(false);
  });

  it('2. cross-business deletion returns 404 and leaves the party untouched', async () => {
    store.parties.set(PARTY_ID, seedParty());
    const { partyService } = await buildServices(
      createFakeDataSource(store, partyLockMgr),
    );

    await expect(
      partyService.delete(PARTY_ID, OTHER_BIZ),
    ).rejects.toBeInstanceOf(NotFoundException);
    expect(store.parties.has(PARTY_ID)).toBe(true);
  });

  it('4. deletion with existing ledger entries removes the party and every entry', async () => {
    store.parties.set(PARTY_ID, seedParty());
    store.entries.set('e1', seedEntry('e1'));
    store.entries.set('e2', seedEntry('e2', { amount: 200, type: 'GOT' }));
    const { partyService } = await buildServices(
      createFakeDataSource(store, partyLockMgr),
    );

    await partyService.delete(PARTY_ID, BIZ);

    expect(store.parties.has(PARTY_ID)).toBe(false);
    expect(store.entries.size).toBe(0);
  });

  it('5. deletion with existing bills and balance history removes everything', async () => {
    store.parties.set(PARTY_ID, seedParty());
    store.bills.set(
      'b1',
      Object.assign(new Bill(), {
        id: 'b1',
        businessId: BIZ,
        partyId: PARTY_ID,
        totalAmount: 100,
      }),
    );
    store.history.push(
      Object.assign(new BalanceHistory(), {
        id: 'h1',
        businessId: BIZ,
        partyId: PARTY_ID,
        previousBalance: 0,
        newBalance: 500,
      }),
    );
    const { partyService } = await buildServices(
      createFakeDataSource(store, partyLockMgr),
    );

    await partyService.delete(PARTY_ID, BIZ);

    expect(store.parties.has(PARTY_ID)).toBe(false);
    expect(store.bills.size).toBe(0);
    expect(store.history).toHaveLength(0);
  });

  describe('6-8. concurrent party-delete + ledger-create for the same party', () => {
    /**
     * PartyService.delete() awaits a plain, unlocked existence check
     * (findOne) before it ever opens its transaction; LedgerService.create()
     * has no such step and goes straight for the lock. That means passing
     * one call before the other to Promise.all does not reliably control
     * which one actually acquires the party lock first — so instead of
     * guessing at timing, these tests instrument the exact moment each side
     * acquires the lock (onPartyLocked) to deterministically prove
     * serialisation, then separately assert the resulting state is safe.
     */
    it('create serialises behind an in-progress party deletion', async () => {
      store.parties.set(PARTY_ID, seedParty());
      store.entries.set('e1', seedEntry('e1'));

      let deleteHasLock = false;
      let deleteFinished = false;
      let lockAcquisitions = 0;
      let createSawDeleteFinishedFirst: boolean | null = null;
      const dataSource = createFakeDataSource(store, partyLockMgr, {
        onPartyLocked: () => {
          lockAcquisitions += 1;
          if (lockAcquisitions === 1) {
            deleteHasLock = true;
          } else if (lockAcquisitions === 2) {
            // The second acquisition can only be create()'s — capture
            // whether delete() had already fully finished (committed) by
            // the moment create() got the lock.
            createSawDeleteFinishedFirst = deleteFinished;
          }
        },
      });
      const { partyService, ledgerService } = await buildServices(dataSource);

      const deleteCall = partyService.delete(PARTY_ID, BIZ).then((r) => {
        deleteFinished = true;
        return r;
      });

      // Don't start the create until delete's transaction has actually
      // acquired the lock (not merely been called) — otherwise delete's
      // extra pre-transaction hop could let create's lock attempt land
      // first, which would prove nothing about serialisation.
      await waitFor(() => deleteHasLock, 'delete() to acquire the party lock');

      const createCall = ledgerService.create(BIZ, {
        partyId: PARTY_ID,
        amount: 100,
        type: 'GAVE',
      });

      await deleteCall;
      await expect(createCall).rejects.toBeInstanceOf(NotFoundException);

      // 7. Proof of real serialisation, not just a safe-looking end state:
      // create()'s lock acquisition only ever happened after delete()'s
      // transaction had already committed.
      expect(createSawDeleteFinishedFirst).toBe(true);

      // 6 & 8. No raw FK violation surfaced, and the end state is
      // consistent: party and every entry are gone.
      expect(store.parties.has(PARTY_ID)).toBe(false);
      expect(store.entries.size).toBe(0);
    });

    it('deletion serialises behind an in-progress ledger create, and then safely cleans up what it committed', async () => {
      store.parties.set(PARTY_ID, seedParty());
      store.entries.set('e1', seedEntry('e1'));

      let createHasLock = false;
      let createFinished = false;
      let lockAcquisitions = 0;
      let deleteSawCreateFinishedFirst: boolean | null = null;
      const dataSource = createFakeDataSource(store, partyLockMgr, {
        onPartyLocked: () => {
          lockAcquisitions += 1;
          if (lockAcquisitions === 1) {
            createHasLock = true;
          } else if (lockAcquisitions === 2) {
            deleteSawCreateFinishedFirst = createFinished;
          }
        },
      });
      const { partyService, ledgerService } = await buildServices(dataSource);

      const createCall = ledgerService
        .create(BIZ, { partyId: PARTY_ID, amount: 100, type: 'GAVE' })
        .then((r) => {
          createFinished = true;
          return r;
        });

      await waitFor(() => createHasLock, 'create() to acquire the party lock');

      const deleteCall = partyService.delete(PARTY_ID, BIZ);

      await createCall;
      await expect(deleteCall).resolves.toBeUndefined();

      // Proof of serialisation in this direction too.
      expect(deleteSawCreateFinishedFirst).toBe(true);

      // 8. Internally consistent: the party is gone, and so is the entry
      // create() committed just before delete() ran its (now-locked, fresh)
      // dependent lookups — no orphaned ledger_entries row survives.
      expect(store.parties.has(PARTY_ID)).toBe(false);
      expect(store.entries.size).toBe(0);
    });
  });

  it('9. a failed deletion transaction leaves no partial cleanup', async () => {
    store.parties.set(PARTY_ID, seedParty());
    store.bills.set(
      'b1',
      Object.assign(new Bill(), {
        id: 'b1',
        businessId: BIZ,
        partyId: PARTY_ID,
        totalAmount: 100,
      }),
    );
    store.entries.set('e1', seedEntry('e1'));
    // Fail on the first remove() call — the bills removal, the first mutating
    // step after the lock is acquired.
    const dataSource = createFakeDataSource(store, partyLockMgr, {
      failOnCall: { method: 'remove', count: 1 },
    });
    const { partyService } = await buildServices(dataSource);

    await expect(partyService.delete(PARTY_ID, BIZ)).rejects.toThrow(
      'simulated failure before commit',
    );

    // Nothing partially cleaned up: party, bill and entry all still present.
    expect(store.parties.has(PARTY_ID)).toBe(true);
    expect(store.bills.has('b1')).toBe(true);
    expect(store.entries.has('e1')).toBe(true);
  });
});
