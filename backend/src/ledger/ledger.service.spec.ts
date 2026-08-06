import { ConflictException } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { LedgerService } from './ledger.service';
import { LedgerEntry } from './ledger-entry.entity';
import { BalanceHistory } from './balance-history.entity';
import { Party } from '../party/party.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * Client-supplied primary keys on ledger create.
 *
 * The property that matters: a replayed write must return the stored entry and
 * leave the running balance untouched. Re-applying the delta would double-count
 * money, which for a ledger app is the worst available failure.
 */
describe('LedgerService — idempotent create', () => {
  let service: LedgerService;
  let entries: LedgerEntry[];
  let history: BalanceHistory[];
  let party: Party;
  let sendSms: jest.Mock;

  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';
  const PARTY_ID = '33333333-3333-4333-8333-333333333333';
  const CLIENT_ID = '44444444-4444-4444-8444-444444444444';

  const dto = {
    partyId: PARTY_ID,
    amount: 500,
    type: 'GAVE' as const,
  };

  beforeEach(async () => {
    entries = [];
    history = [];
    sendSms = jest.fn().mockResolvedValue(true);

    party = Object.assign(new Party(), {
      id: PARTY_ID,
      businessId: BUSINESS_A,
      type: 'CUSTOMER',
      phone: '+919876543210',
      currentBalance: 0,
    });

    // Minimal in-memory stand-in for the transactional EntityManager.
    const manager = {
      createQueryBuilder: () => ({
        setLock: () => ({
          where: (_sql: string, params: any) => ({
            getOne: async () =>
              params.id === party.id && params.businessId === party.businessId
                ? party
                : null,
          }),
        }),
      }),
      findOneBy: async (Entity: any, where: any) => {
        if (Entity !== LedgerEntry) return null;
        return entries.find((e) => e.id === where.id) ?? null;
      },
      create: (Entity: any, obj: any) => Object.assign(new Entity(), obj),
      save: async (entity: any) => {
        if (entity instanceof LedgerEntry) {
          entity.id = entity.id ?? `server-generated-${entries.length}`;
          entries.push(entity);
        } else if (entity instanceof BalanceHistory) {
          history.push(entity);
        }
        // Party saves mutate the shared `party` object in place.
        return entity;
      },
    };

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        LedgerService,
        { provide: getRepositoryToken(LedgerEntry), useValue: {} },
        { provide: getRepositoryToken(Party), useValue: {} },
        { provide: SmsService, useValue: { sendSms } },
        {
          provide: DataSource,
          useValue: { transaction: (cb: any) => cb(manager) },
        },
      ],
    }).compile();

    service = moduleRef.get(LedgerService);
  });

  it('stores the entry under the id the client supplied', async () => {
    const entry = await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(entry.id).toBe(CLIENT_ID);
    expect(entries).toHaveLength(1);
  });

  it('still generates an id when the client omits one', async () => {
    const entry = await service.create(BUSINESS_A, dto);

    expect(entry.id).toBeDefined();
    expect(entries).toHaveLength(1);
  });

  it('applies the balance delta exactly once on first write', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(Number(party.currentBalance)).toBe(500);
  });

  // Pins the sign convention the reminder scheduler and the app's
  // "You will get" / "You will give" labels both depend on.
  describe('sign convention', () => {
    it('CUSTOMER + GAVE is positive — they owe us', async () => {
      await service.create(BUSINESS_A, { ...dto, type: 'GAVE' });

      expect(Number(party.currentBalance)).toBeGreaterThan(0);
    });

    it('CUSTOMER + GOT is negative — we owe them', async () => {
      await service.create(BUSINESS_A, { ...dto, type: 'GOT' });

      expect(Number(party.currentBalance)).toBeLessThan(0);
    });

    it('SUPPLIER inverts it — GOT is positive, meaning we owe them', async () => {
      party.type = 'SUPPLIER';

      await service.create(BUSINESS_A, { ...dto, type: 'GOT' });

      expect(Number(party.currentBalance)).toBeGreaterThan(0);
    });
  });

  it('does NOT re-apply the balance delta on replay', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    // Three identical sends, one logical write.
    expect(Number(party.currentBalance)).toBe(500);
    expect(entries).toHaveLength(1);
  });

  it('writes no extra balance-history row on replay', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(history).toHaveLength(1);
  });

  it('does not re-send the customer SMS on replay', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(sendSms).toHaveBeenCalledTimes(1);
  });

  it('returns the originally stored entry on replay', async () => {
    const first = await service.create(BUSINESS_A, {
      ...dto,
      id: CLIENT_ID,
      note: 'original',
    });
    const replay = await service.create(BUSINESS_A, {
      ...dto,
      id: CLIENT_ID,
      note: 'changed',
    });

    expect(replay.id).toBe(first.id);
    expect(replay.note).toBe('original');
  });

  it('rejects an id already held by another business', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    party.businessId = BUSINESS_B;

    await expect(
      service.create(BUSINESS_B, { ...dto, id: CLIENT_ID }),
    ).rejects.toBeInstanceOf(ConflictException);

    expect(entries).toHaveLength(1);
  });

  describe('findByBusiness (cashbook feed)', () => {
    let findArgs: any;

    beforeEach(() => {
      const repo = (service as any).ledgerRepository;
      repo.find = jest.fn(async (args: any) => ((findArgs = args), []));
    });

    it('scopes to the business, newest first, with the party joined', async () => {
      await service.findByBusiness(BUSINESS_A);

      expect(findArgs.where.businessId).toBe(BUSINESS_A);
      expect(findArgs.order).toEqual({ createdAt: 'DESC' });
      expect(findArgs.relations).toEqual({ party: true });
    });

    it('caps the row count so one call cannot pull a whole ledger', async () => {
      await service.findByBusiness(BUSINESS_A);

      expect(findArgs.take).toBe(500);
    });

    it('applies no date filter when neither bound is given', async () => {
      await service.findByBusiness(BUSINESS_A);

      expect(findArgs.where.createdAt).toBeUndefined();
    });

    it('filters between both bounds', async () => {
      await service.findByBusiness(BUSINESS_A, {
        from: '2026-01-01T00:00:00.000Z',
        to: '2026-02-01T00:00:00.000Z',
      });

      // Between(from, to) keeps both endpoints in its value array.
      expect(findArgs.where.createdAt.value).toEqual([
        new Date('2026-01-01T00:00:00.000Z'),
        new Date('2026-02-01T00:00:00.000Z'),
      ]);
    });

    it.each([
      ['from only', { from: '2026-01-01T00:00:00.000Z' }, 'moreThanOrEqual'],
      ['to only', { to: '2026-02-01T00:00:00.000Z' }, 'lessThanOrEqual'],
    ])('handles %s', async (_label, range, expectedType) => {
      await service.findByBusiness(BUSINESS_A, range);

      expect(findArgs.where.createdAt.type).toBe(expectedType);
    });
  });

  it('distinct ids still accumulate normally', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    await service.create(BUSINESS_A, {
      ...dto,
      id: '55555555-5555-4555-8555-555555555555',
    });

    expect(Number(party.currentBalance)).toBe(1000);
    expect(entries).toHaveLength(2);
  });
});
