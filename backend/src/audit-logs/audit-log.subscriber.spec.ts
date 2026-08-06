import { DataSource } from 'typeorm';
import { Broadcaster } from 'typeorm/subscriber/Broadcaster';
import { AuditLogSubscriber } from './audit-log.subscriber';
import { AuditLog, AuditAction } from './audit-log.entity';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { Bill } from '../bills/entities/bill.entity';
import { Party } from '../party/party.entity';
import { auditContext, setAuditUser } from './audit-context';

/**
 * The audit subscriber never ran, for two independent reasons:
 *
 *  1. It was declared only as a Nest provider, so TypeORM — which builds its
 *     subscriber list from `options.subscribers` — never attached it.
 *  2. `listenTo()` returned the string 'all', which TypeORM's isAllowedSubscriber
 *     rejects for every entity.
 *
 * Fixing one without the other leaves it dead, so both are asserted here. The
 * listenTo check runs TypeORM's own predicate rather than a reimplementation.
 */
describe('AuditLogSubscriber', () => {
  /** A DataSource is inert until .initialize(), so constructing one is safe. */
  const makeDataSource = () =>
    new DataSource({ type: 'postgres', url: 'postgres://unused/db' });

  describe('registration', () => {
    it('attaches itself to the DataSource on construction', () => {
      const dataSource = makeDataSource();
      expect(dataSource.subscribers).toHaveLength(0);

      const subscriber = new AuditLogSubscriber(dataSource);

      expect(dataSource.subscribers).toContain(subscriber);
    });
  });

  describe('entity filtering, via TypeORM\'s own predicate', () => {
    const isAllowed = (target: Function) => {
      const subscriber = new AuditLogSubscriber(makeDataSource());
      // Broadcaster only needs a queryRunner for broadcasting, not for this check.
      const broadcaster = new Broadcaster({} as any) as any;
      return broadcaster.isAllowedSubscriber(subscriber, target);
    };

    it.each([[LedgerEntry], [Bill], [Party], [AuditLog]])(
      'TypeORM allows the subscriber to observe %p',
      (target) => {
        expect(isAllowed(target)).toBe(true);
      },
    );

    it('rejects a subscriber that reports listenTo() === "all"', () => {
      // The previous implementation, kept here so the regression stays visible.
      const legacy = { listenTo: () => 'all' } as any;
      const broadcaster = new Broadcaster({} as any) as any;

      expect(broadcaster.isAllowedSubscriber(legacy, LedgerEntry)).toBe(false);
    });
  });

  describe('writing audit rows', () => {
    let subscriber: AuditLogSubscriber;
    let saved: any[];
    let manager: any;

    const ledgerEntry = (over: Partial<LedgerEntry> = {}) =>
      Object.assign(new LedgerEntry(), {
        id: 'entry-1',
        businessId: 'biz-1',
        amount: 500,
        type: 'GAVE',
        ...over,
      });

    const event = (entity: any, extra: Record<string, any> = {}) => ({
      entity,
      manager,
      metadata: { name: entity?.constructor?.name },
      ...extra,
    });

    beforeEach(() => {
      saved = [];
      manager = { save: jest.fn(async (_E: any, row: any) => (saved.push(row), row)) };
      subscriber = new AuditLogSubscriber(makeDataSource());
    });

    it('records a CREATE for a ledger entry', async () => {
      await subscriber.afterInsert(event(ledgerEntry()) as any);

      expect(saved).toHaveLength(1);
      expect(saved[0]).toMatchObject({
        action: AuditAction.CREATE,
        entityName: 'LedgerEntry',
        entityId: 'entry-1',
      });
    });

    it('records an UPDATE with both states', async () => {
      const before = ledgerEntry({ amount: 100 });
      const after = ledgerEntry({ amount: 900 });

      await subscriber.afterUpdate(
        event(after, { databaseEntity: before }) as any,
      );

      expect(saved[0]).toMatchObject({ action: AuditAction.UPDATE });
      expect(saved[0].previousState.amount).toBe(100);
      expect(saved[0].newState.amount).toBe(900);
    });

    it('records a DELETE using the database entity', async () => {
      const removed = ledgerEntry();

      await subscriber.afterRemove(
        event(undefined, { databaseEntity: removed, metadata: { name: 'LedgerEntry' } }) as any,
      );

      expect(saved[0]).toMatchObject({
        action: AuditAction.DELETE,
        entityId: 'entry-1',
      });
    });

    it('records bills too', async () => {
      const bill = Object.assign(new Bill(), { id: 'bill-1', totalAmount: 250 });

      await subscriber.afterInsert(event(bill) as any);

      expect(saved[0]).toMatchObject({ entityName: 'Bill', entityId: 'bill-1' });
    });

    it('ignores entities outside the audited set', async () => {
      await subscriber.afterInsert(
        event(Object.assign(new Party(), { id: 'party-1' })) as any,
      );

      expect(saved).toHaveLength(0);
    });

    it('does not audit AuditLog itself, so there is no recursion', async () => {
      await subscriber.afterInsert(
        event(Object.assign(new AuditLog(), { id: 'audit-1' })) as any,
      );

      expect(saved).toHaveLength(0);
    });

    describe('performedBy attribution', () => {
      it('uses the authenticated user from the request context', async () => {
        await auditContext.run({}, async () => {
          setAuditUser('user-42');
          await subscriber.afterInsert(event(ledgerEntry()) as any);
        });

        expect(saved[0].performedBy).toBe('user-42');
      });

      it('falls back to SYSTEM outside a request, e.g. the cron', async () => {
        await subscriber.afterInsert(event(ledgerEntry()) as any);

        expect(saved[0].performedBy).toBe('SYSTEM');
      });

      it('falls back to SYSTEM when a request is unauthenticated', async () => {
        await auditContext.run({}, async () => {
          await subscriber.afterInsert(event(ledgerEntry()) as any);
        });

        expect(saved[0].performedBy).toBe('SYSTEM');
      });

      it('keeps concurrent requests attributed separately', async () => {
        const request = (userId: string, id: string) =>
          auditContext.run({}, async () => {
            setAuditUser(userId);
            // Yield, so the two requests genuinely interleave.
            await new Promise((r) => setImmediate(r));
            await subscriber.afterInsert(event(ledgerEntry({ id })) as any);
          });

        await Promise.all([request('user-a', 'entry-a'), request('user-b', 'entry-b')]);

        const byId = Object.fromEntries(
          saved.map((row) => [row.entityId, row.performedBy]),
        );
        expect(byId).toEqual({ 'entry-a': 'user-a', 'entry-b': 'user-b' });
      });
    });
  });
});
