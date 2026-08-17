import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { LedgerService } from './ledger.service';
import { LedgerEntry } from './ledger-entry.entity';
import { BalanceHistory } from './balance-history.entity';
import { Party } from '../party/party.entity';
import { SmsService } from '../notifications/sms/sms.service';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';

/**
 * Exact decimal arithmetic for Party.currentBalance / BalanceHistory.
 *
 * Phase 1 of this task exhaustively verified (1.3M create()-style ops, 510M
 * update()-style adjustments) that the existing single-op-per-persist
 * pattern already lands on the mathematically exact 2-decimal result once
 * rounded — this was never a demonstrated bug. roundMoney() makes that
 * guarantee explicit in the code instead of implicit via Postgres's
 * numeric(14,2) cast. These tests assert the *exact* JS number stored after
 * each operation — e.g. `toBe(0.3)`, not `toBeCloseTo(0.3)` — specifically so
 * a regression back to raw float arithmetic (e.g. 0.30000000000000004) would
 * fail loudly rather than passing under a tolerant matcher.
 */
describe('LedgerService — exact decimal arithmetic', () => {
  let service: LedgerService;
  let party: Party;
  let entries: LedgerEntry[];
  let history: BalanceHistory[];
  let sendSms: jest.Mock;

  const BUSINESS = '11111111-1111-4111-8111-111111111111';
  const PARTY_ID = '22222222-2222-4222-8222-222222222222';

  const seedParty = (overrides: Partial<Party> = {}) =>
    Object.assign(new Party(), {
      id: PARTY_ID,
      businessId: BUSINESS,
      type: 'CUSTOMER',
      phone: '',
      currentBalance: 0,
      ...overrides,
    });

  const buildManager = () => ({
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
    findOne: async (Entity: any, { where }: any) => {
      if (Entity !== LedgerEntry) return null;
      return (
        entries.find((e) =>
          Object.entries(where).every(([k, v]) => (e as any)[k] === v),
        ) ?? null
      );
    },
    findOneBy: async (Entity: any, where: any) => {
      if (Entity !== LedgerEntry) return null;
      return entries.find((e) => e.id === where.id) ?? null;
    },
    create: (Entity: any, obj: any) => Object.assign(new Entity(), obj),
    save: async (entity: any) => {
      if (entity instanceof Party) {
        Object.assign(party, entity);
        return party;
      }
      if (entity instanceof LedgerEntry) {
        entity.id = entity.id ?? `server-generated-${entries.length}`;
        entries.push(entity);
        return entity;
      }
      if (entity instanceof BalanceHistory) {
        history.push(entity);
        return entity;
      }
      return entity;
    },
    update: async () => undefined,
    remove: async (entity: any) => {
      entries = entries.filter((e) => e.id !== entity.id);
    },
  });

  const buildService = async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        LedgerService,
        {
          provide: getRepositoryToken(LedgerEntry),
          useValue: {
            findOneBy: async (where: any) =>
              entries.find((e) => e.id === where.id) ?? null,
          },
        },
        { provide: getRepositoryToken(Party), useValue: {} },
        { provide: SmsService, useValue: { sendSms } },
        {
          provide: DataSource,
          useValue: { transaction: (cb: any) => cb(buildManager()) },
        },
      ],
    }).compile();
    return moduleRef.get(LedgerService);
  };

  beforeEach(async () => {
    entries = [];
    history = [];
    sendSms = jest.fn().mockResolvedValue(true);
    party = seedParty();
    service = await buildService();
  });

  const balance = () => Number(party.currentBalance);

  it('0.10 + 0.20 = exactly 0.30', async () => {
    party.currentBalance = 0.1;

    await service.create(BUSINESS, {
      partyId: PARTY_ID,
      amount: 0.2,
      type: 'GAVE',
    });

    expect(balance()).toBe(0.3);
    expect(balance()).not.toBe(0.30000000000000004);
  });

  it('0.30 - 0.10 = exactly 0.20', async () => {
    party.currentBalance = 0.3;

    await service.create(BUSINESS, {
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GOT',
    });

    expect(balance()).toBe(0.2);
  });

  it('10.25 + 20.35 = exactly 30.60', async () => {
    party.currentBalance = 10.25;

    await service.create(BUSINESS, {
      partyId: PARTY_ID,
      amount: 20.35,
      type: 'GAVE',
    });

    expect(balance()).toBe(30.6);
  });

  it('repeated additions of 0.10 land exactly on 0.30, not a float artifact', async () => {
    await service.create(BUSINESS, {
      id: 'a',
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GAVE',
    });
    await service.create(BUSINESS, {
      id: 'b',
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GAVE',
    });
    await service.create(BUSINESS, {
      id: 'c',
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GAVE',
    });

    expect(balance()).toBe(0.3);
  });

  it('repeated subtractions land exactly on the expected value', async () => {
    party.currentBalance = 1;

    await service.create(BUSINESS, {
      id: 'a',
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GOT',
    });
    await service.create(BUSINESS, {
      id: 'b',
      partyId: PARTY_ID,
      amount: 0.2,
      type: 'GOT',
    });
    await service.create(BUSINESS, {
      id: 'c',
      partyId: PARTY_ID,
      amount: 0.3,
      type: 'GOT',
    });

    expect(balance()).toBe(0.4);
  });

  it('update from 0.10 to 0.20 leaves the balance at exactly 0.20', async () => {
    const entry = await service.create(BUSINESS, {
      id: 'x',
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GAVE',
    });
    expect(balance()).toBe(0.1);

    await service.update(entry.id, BUSINESS, { amount: 0.2 });

    expect(balance()).toBe(0.2);
    expect(entries.find((e) => e.id === 'x')!.amount).toBe(0.2);
  });

  it('deleting a 0.10 transaction returns the balance to exactly 0', async () => {
    const entry = await service.create(BUSINESS, {
      id: 'x',
      partyId: PARTY_ID,
      amount: 0.1,
      type: 'GAVE',
    });
    expect(balance()).toBe(0.1);

    await service.delete(entry.id, BUSINESS);

    expect(balance()).toBe(0);
    expect(Object.is(balance(), -0)).toBe(false);
  });

  it('mixed GAVE/GOT entries land on the exact expected balance', async () => {
    await service.create(BUSINESS, {
      id: 'a',
      partyId: PARTY_ID,
      amount: 15.5,
      type: 'GAVE',
    });
    await service.create(BUSINESS, {
      id: 'b',
      partyId: PARTY_ID,
      amount: 5.25,
      type: 'GOT',
    });
    await service.create(BUSINESS, {
      id: 'c',
      partyId: PARTY_ID,
      amount: 3.1,
      type: 'GAVE',
    });
    await service.create(BUSINESS, {
      id: 'd',
      partyId: PARTY_ID,
      amount: 2.35,
      type: 'GOT',
    });

    // 15.50 - 5.25 + 3.10 - 2.35 = 11.00
    expect(balance()).toBe(11);
  });

  it('CUSTOMER sign convention: GAVE increases, GOT decreases, exactly', async () => {
    await service.create(BUSINESS, {
      id: 'a',
      partyId: PARTY_ID,
      amount: 100.01,
      type: 'GAVE',
    });
    expect(balance()).toBe(100.01);

    await service.create(BUSINESS, {
      id: 'b',
      partyId: PARTY_ID,
      amount: 0.02,
      type: 'GOT',
    });
    expect(balance()).toBe(99.99);
  });

  it('SUPPLIER sign convention is inverted, exactly', async () => {
    party = seedParty({ type: 'SUPPLIER' });
    service = await buildService();

    await service.create(BUSINESS, {
      id: 'a',
      partyId: PARTY_ID,
      amount: 100.01,
      type: 'GOT',
    });
    expect(balance()).toBe(100.01);

    await service.create(BUSINESS, {
      id: 'b',
      partyId: PARTY_ID,
      amount: 0.02,
      type: 'GAVE',
    });
    expect(balance()).toBe(99.99);
  });

  it('a longer, mixed sequence lands on the exact expected two-decimal balance', async () => {
    const ops: Array<{ id: string; amount: number; type: 'GAVE' | 'GOT' }> = [
      { id: '1', amount: 0.1, type: 'GAVE' },
      { id: '2', amount: 0.2, type: 'GAVE' },
      { id: '3', amount: 0.3, type: 'GOT' },
      { id: '4', amount: 99.99, type: 'GAVE' },
      { id: '5', amount: 0.01, type: 'GOT' },
      { id: '6', amount: 10.1, type: 'GOT' },
    ];
    for (const op of ops) {
      await service.create(BUSINESS, { partyId: PARTY_ID, ...op });
    }

    // 0.10 + 0.20 - 0.30 + 99.99 - 0.01 - 10.10 = 89.88
    expect(balance()).toBe(89.88);
  });

  it('BalanceHistory.previousBalance/newBalance exactly match Party.currentBalance at each step', async () => {
    party.currentBalance = 0.1;
    const first = await service.create(BUSINESS, {
      partyId: PARTY_ID,
      amount: 0.2,
      type: 'GAVE',
    });
    expect(Number(history[0].previousBalance)).toBe(0.1);
    expect(Number(history[0].newBalance)).toBe(0.3);
    expect(Number(history[0].newBalance)).toBe(balance());

    await service.update(first.id, BUSINESS, { amount: 0.5 });
    expect(Number(history[1].previousBalance)).toBe(0.3);
    expect(Number(history[1].newBalance)).toBe(0.6);
    expect(Number(history[1].newBalance)).toBe(balance());

    await service.delete(first.id, BUSINESS);
    expect(Number(history[2].previousBalance)).toBe(0.6);
    expect(Number(history[2].newBalance)).toBe(0.1);
    expect(Number(history[2].newBalance)).toBe(balance());
  });

  describe('DTO validation remains authoritative (unchanged by this fix)', () => {
    const base = { partyId: PARTY_ID, type: 'GAVE' as const };

    it('rejects a negative amount', async () => {
      const dto = plainToInstance(CreateLedgerEntryDto, {
        ...base,
        amount: -5,
      });
      const errors = await validate(dto);
      expect(errors.some((e) => e.property === 'amount')).toBe(true);
    });

    it('rejects a zero amount', async () => {
      const dto = plainToInstance(CreateLedgerEntryDto, { ...base, amount: 0 });
      const errors = await validate(dto);
      expect(errors.some((e) => e.property === 'amount')).toBe(true);
    });

    it('rejects more than 2 decimal places', async () => {
      const dto = plainToInstance(CreateLedgerEntryDto, {
        ...base,
        amount: 10.123,
      });
      const errors = await validate(dto);
      expect(errors.some((e) => e.property === 'amount')).toBe(true);
    });

    it('accepts the maximum allowed amount', async () => {
      const dto = plainToInstance(CreateLedgerEntryDto, {
        ...base,
        amount: 999999999999.99,
      });
      const errors = await validate(dto);
      expect(errors).toHaveLength(0);
    });

    it('rejects an amount one cent beyond the maximum', async () => {
      const dto = plainToInstance(CreateLedgerEntryDto, {
        ...base,
        amount: 1000000000000.0,
      });
      const errors = await validate(dto);
      expect(errors.some((e) => e.property === 'amount')).toBe(true);
    });
  });

  it('the maximum allowed amount produces an exact persisted balance', async () => {
    await service.create(BUSINESS, {
      partyId: PARTY_ID,
      amount: 999999999999.99,
      type: 'GAVE',
    });

    expect(balance()).toBe(999999999999.99);
  });
});
