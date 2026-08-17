import { ConflictException, NotFoundException } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { BillsService } from './bills.service';
import { Bill } from './entities/bill.entity';
import { Party } from '../party/party.entity';

/** Postgres unique_violation. */
const POSTGRES_UNIQUE_VIOLATION = '23505';

/**
 * Client-supplied primary keys on bill create.
 *
 * POST /bills previously had no idempotency key at all, unlike party and
 * ledger-entry creation — a retry after a network timeout created a second
 * Bill row, which the Sales report (summing Bill.totalAmount directly) would
 * then double-count. This mirrors PartyService's exact idempotency pattern:
 * a pre-check for the common sequential-retry case, and a unique-violation
 * catch — driven by the bills.id primary key, not by the check — for the
 * case where two requests race past the check at the same time.
 */
describe('BillsService — client-supplied idempotency key', () => {
  let service: BillsService;
  let rows: Bill[];

  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';
  const PARTY_ID = '22222222-2222-4222-8222-222222222222';
  const CLIENT_ID = '33333333-3333-4333-8333-333333333333';

  const dto = (overrides: Record<string, any> = {}) => ({
    partyId: PARTY_ID,
    totalAmount: 1250.5,
    items: [{ description: 'Basmati Rice 5kg', quantity: 2, price: 480 }],
    isGst: true,
    ...overrides,
  });

  beforeEach(async () => {
    rows = [];

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        BillsService,
        {
          provide: getRepositoryToken(Bill),
          useValue: {
            create: (obj: Partial<Bill>) => Object.assign(new Bill(), obj),
            // Models the real primary-key constraint: inserting an id that
            // already exists in the table fails, exactly like Postgres would.
            save: jest.fn(async (b: Bill) => {
              if (b.id && rows.some((r) => r.id === b.id)) {
                throw Object.assign(
                  new Error('duplicate key value violates unique constraint'),
                  {
                    code: POSTGRES_UNIQUE_VIOLATION,
                  },
                );
              }
              const saved = Object.assign(new Bill(), b, {
                id: b.id ?? `server-generated-${rows.length}`,
              });
              rows.push(saved);
              return saved;
            }),
            findOneBy: jest.fn(
              async (where: any) =>
                rows.find((r) =>
                  Object.entries(where).every(([k, v]) => (r as any)[k] === v),
                ) ?? null,
            ),
          },
        },
        {
          provide: getRepositoryToken(Party),
          useValue: {
            findOne: async ({ where }: any) =>
              where.id === PARTY_ID && where.businessId === BUSINESS_A
                ? Object.assign(new Party(), {
                    id: PARTY_ID,
                    businessId: BUSINESS_A,
                  })
                : null,
          },
        },
      ],
    }).compile();

    service = moduleRef.get(BillsService);
  });

  it('1. first creation succeeds, stored under the client-supplied id', async () => {
    const bill = await service.create(dto({ id: CLIENT_ID }), BUSINESS_A);

    expect(bill.id).toBe(CLIENT_ID);
    expect(Number(bill.totalAmount)).toBe(1250.5);
    expect(rows).toHaveLength(1);
  });

  it('still generates an id when the client omits one (unaffected legacy path)', async () => {
    const bill = await service.create(dto(), BUSINESS_A);

    expect(bill.id).toBeDefined();
    expect(rows).toHaveLength(1);
  });

  it('2. sequential retry with the same id returns the stored bill instead of creating another', async () => {
    const first = await service.create(dto({ id: CLIENT_ID }), BUSINESS_A);
    const retry = await service.create(dto({ id: CLIENT_ID }), BUSINESS_A);

    expect(retry.id).toBe(first.id);
    expect(rows).toHaveLength(1);
  });

  it('a replay does not re-apply values even if the retried body differs', async () => {
    await service.create(
      dto({ id: CLIENT_ID, totalAmount: 1250.5 }),
      BUSINESS_A,
    );
    const retry = await service.create(
      dto({ id: CLIENT_ID, totalAmount: 999 }),
      BUSINESS_A,
    );

    // Create is create, not upsert — the stored row wins.
    expect(Number(retry.totalAmount)).toBe(1250.5);
    expect(rows).toHaveLength(1);
  });

  it('3. concurrent creation with the same id converges on exactly one row', async () => {
    const requestA = service.create(dto({ id: CLIENT_ID }), BUSINESS_A);
    const requestB = service.create(dto({ id: CLIENT_ID }), BUSINESS_A);

    const [resultA, resultB] = await Promise.all([requestA, requestB]);

    // Both requests succeed and agree on the same row — neither sees a bare
    // conflict, matching requirement 4/15.
    expect(resultA.id).toBe(CLIENT_ID);
    expect(resultB.id).toBe(CLIENT_ID);
    expect(rows).toHaveLength(1);
  });

  it('4. different ids create two independent bills', async () => {
    await service.create(dto({ id: CLIENT_ID }), BUSINESS_A);
    await service.create(
      dto({ id: '44444444-4444-4444-8444-444444444444' }),
      BUSINESS_A,
    );

    expect(rows).toHaveLength(2);
  });

  it('5. an id already held by another business cannot be replayed cross-tenant, and leaks nothing', async () => {
    await service.create(dto({ id: CLIENT_ID }), BUSINESS_A);

    const attempt = service.create(dto({ id: CLIENT_ID }), BUSINESS_B);

    await expect(attempt).rejects.toBeInstanceOf(ConflictException);
    await expect(attempt).rejects.toThrow('Bill id is already in use');
    // Nothing about business A's bill (amount, items) is disclosed.
    await expect(attempt).rejects.not.toThrow(/1250.5/);
    expect(rows).toHaveLength(1);
  });

  it("rejects party lookup for a business other than the caller's, even on a fresh id", async () => {
    await expect(
      service.create(dto({ id: CLIENT_ID }), BUSINESS_B),
    ).rejects.toBeInstanceOf(NotFoundException);
    expect(rows).toHaveLength(0);
  });

  it('8. a failed insert does not poison the id — a subsequent retry still succeeds', async () => {
    const repo = (service as any).billsRepository;
    repo.save.mockImplementationOnce(async () => {
      throw new Error('connection reset');
    });

    await expect(
      service.create(dto({ id: CLIENT_ID }), BUSINESS_A),
    ).rejects.toThrow('connection reset');
    expect(rows).toHaveLength(0);

    const retry = await service.create(dto({ id: CLIENT_ID }), BUSINESS_A);
    expect(retry.id).toBe(CLIENT_ID);
    expect(rows).toHaveLength(1);
  });
});
