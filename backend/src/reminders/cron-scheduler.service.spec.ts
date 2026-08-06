import { getRepositoryToken } from '@nestjs/typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { CronSchedulerService } from './cron-scheduler.service';
import { RemindersService } from './reminders.service';
import { Party } from '../party/party.entity';

/**
 * Direction of the daily collection reminder.
 *
 * Sign convention, set by LedgerService.create and mirrored by the app's
 * "You will get" / "You will give" labels:
 *
 *   CUSTOMER  positive balance -> they owe us   (chase them)
 *   CUSTOMER  negative balance -> we owe them   (do not chase)
 *   SUPPLIER  positive balance -> we owe them   (do not chase)
 *
 * The scheduler previously selected `currentBalance < 0`, i.e. it dunned the
 * people it owed money to.
 *
 * The fake query builder below interprets the captured WHERE clauses against a
 * seeded population, so these assertions are about who actually receives an SMS
 * rather than about the shape of the SQL string.
 */

/** The only clauses this scheduler is expected to build. */
const CLAUSE_PREDICATES: Record<
  string,
  (party: any, params: Record<string, any>) => boolean
> = {
  'party.type = :type': (p, params) => p.type === params.type,
  'party.currentBalance > 0': (p) => Number(p.currentBalance) > 0,
  'party.currentBalance < 0': (p) => Number(p.currentBalance) < 0,
};

describe('CronSchedulerService — reminder targeting', () => {
  let service: CronSchedulerService;
  let sendReminder: jest.Mock;
  let population: any[];

  /** Postgres returns numeric(14,2) as a string; mirror that. */
  const party = (name: string, type: string, balance: string, phone = `+9198765432${name.length}0`) =>
    ({ id: name, name, type, currentBalance: balance, phone });

  const OWES_US = party('Rahul Traders', 'CUSTOMER', '500.00');
  const WE_OWE_THEM = party('Priya Stores', 'CUSTOMER', '-750.00');
  const SETTLED = party('Amit Kirana', 'CUSTOMER', '0.00');
  const SUPPLIER_WE_OWE = party('Wholesale Depot', 'SUPPLIER', '1200.00');
  const SUPPLIER_IN_CREDIT = party('Grain Mills', 'SUPPLIER', '-300.00');

  beforeEach(async () => {
    population = [OWES_US, WE_OWE_THEM, SETTLED, SUPPLIER_WE_OWE, SUPPLIER_IN_CREDIT];
    sendReminder = jest.fn().mockResolvedValue({ success: true, message: 'ok' });

    const makeQueryBuilder = () => {
      const clauses: { sql: string; params: Record<string, any> }[] = [];
      const builder = {
        where: (sql: string, params = {}) => (clauses.push({ sql, params }), builder),
        andWhere: (sql: string, params = {}) => (clauses.push({ sql, params }), builder),
        getMany: async () =>
          population.filter((p) =>
            clauses.every(({ sql, params }) => {
              const predicate = CLAUSE_PREDICATES[sql];
              if (!predicate) {
                throw new Error(`Unrecognised WHERE clause in scheduler: "${sql}"`);
              }
              return predicate(p, params);
            }),
          ),
      };
      return builder;
    };

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        CronSchedulerService,
        { provide: RemindersService, useValue: { sendReminder } },
        {
          provide: getRepositoryToken(Party),
          useValue: { createQueryBuilder: makeQueryBuilder },
        },
      ],
    }).compile();

    service = moduleRef.get(CronSchedulerService);
  });

  const remindedNames = () => sendReminder.mock.calls.map(([, , name]) => name);

  it('reminds a customer who owes us', async () => {
    await service.handleDailyReminders();

    expect(remindedNames()).toContain('Rahul Traders');
  });

  it('does NOT remind a customer we owe money to', async () => {
    await service.handleDailyReminders();

    expect(remindedNames()).not.toContain('Priya Stores');
  });

  it('does not remind a settled customer', async () => {
    await service.handleDailyReminders();

    expect(remindedNames()).not.toContain('Amit Kirana');
  });

  it('does not chase suppliers in either direction', async () => {
    await service.handleDailyReminders();

    expect(remindedNames()).not.toContain('Wholesale Depot');
    expect(remindedNames()).not.toContain('Grain Mills');
  });

  it('reminds exactly the customers in debit to us', async () => {
    await service.handleDailyReminders();

    expect(remindedNames()).toEqual(['Rahul Traders']);
  });

  it('sends the owed amount as a positive number', async () => {
    await service.handleDailyReminders();

    const [phone, amount, name] = sendReminder.mock.calls[0];
    expect(name).toBe('Rahul Traders');
    expect(amount).toBe(500);
    expect(amount).toBeGreaterThan(0);
    expect(phone).toBe(OWES_US.phone);
  });

  it('skips a customer with no phone number', async () => {
    population = [party('No Phone Co', 'CUSTOMER', '900.00', '')];

    await service.handleDailyReminders();

    expect(sendReminder).not.toHaveBeenCalled();
  });

  it('keeps going when one reminder fails', async () => {
    population = [
      party('First Co', 'CUSTOMER', '100.00'),
      party('Second Co', 'CUSTOMER', '200.00'),
    ];
    sendReminder.mockRejectedValueOnce(new Error('gateway down'));

    await expect(service.handleDailyReminders()).resolves.not.toThrow();
    expect(sendReminder).toHaveBeenCalledTimes(2);
  });
});
