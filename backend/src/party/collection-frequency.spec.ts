import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { CreatePartyDto } from './dto/create-party.dto';
import { PartyService } from './party.service';
import { Party } from './party.entity';
import { Business } from '../business/business.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * The party's Daily/Weekly/Monthly bucket, persisted server-side.
 *
 * It previously existed only in the Android client's Room database, so a
 * restored device had nothing to restore it from and every party came back as
 * DAILY — emptying the Weekly and Monthly collections.
 */
describe('PartyService — collection frequency', () => {
  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';

  let service: PartyService;
  let rows: Party[];

  const errorsFor = async (payload: Record<string, unknown>) => {
    const dto = plainToInstance(CreatePartyDto, payload);
    const errors = await validate(dto, {
      whitelist: true,
      forbidNonWhitelisted: true,
    });
    return errors.map((e) => e.property);
  };

  beforeEach(async () => {
    rows = [];
    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        PartyService,
        {
          provide: getRepositoryToken(Party),
          useValue: {
            create: (o: any) => Object.assign(new Party(), o),
            save: jest.fn(async (p: any) => {
              const stored = { ...p, id: p.id ?? `gen-${rows.length}` };
              const at = rows.findIndex((r) => r.id === stored.id);
              if (at >= 0) rows[at] = stored as Party;
              else rows.push(stored as Party);
              return stored;
            }),
            findOneBy: jest.fn(async (where: any) => {
              const found = rows.find((r) => r.id === where.id);
              if (!found) return null;
              if (where.businessId && found.businessId !== where.businessId) return null;
              return found;
            }),
          },
        },
        {
          provide: getRepositoryToken(Business),
          useValue: { findOne: async () => ({ id: BUSINESS_A, name: 'Shop' }) },
        },
        { provide: SmsService, useValue: { sendTransactionalSms: jest.fn() } },
        { provide: DataSource, useValue: { transaction: jest.fn() } },
      ],
    }).compile();

    service = moduleRef.get(PartyService);
  });

  // ─── Validation ─────────────────────────────────────────────

  it.each(['DAILY', 'WEEKLY', 'MONTHLY'])('accepts %s', async (value) => {
    expect(
      await errorsFor({ name: 'A', type: 'CUSTOMER', collectionFrequency: value }),
    ).toEqual([]);
  });

  it('accepts a create that omits it (older clients)', async () => {
    expect(await errorsFor({ name: 'A', type: 'CUSTOMER' })).toEqual([]);
  });

  it('rejects a value outside the three buckets', async () => {
    expect(
      await errorsFor({ name: 'A', type: 'CUSTOMER', collectionFrequency: 'FORTNIGHTLY' }),
    ).toContain('collectionFrequency');
  });

  // ─── Persistence ────────────────────────────────────────────

  it.each([
    ['DAILY'],
    ['WEEKLY'],
    ['MONTHLY'],
  ])('persists %s exactly as sent', async (value) => {
    const party = await service.create(BUSINESS_A, {
      name: 'A',
      type: 'CUSTOMER',
      collectionFrequency: value,
    } as CreatePartyDto);

    expect(party.collectionFrequency).toBe(value);
  });

  it('defaults to DAILY when the client sends nothing', async () => {
    // Matches how such a party already behaved, so nothing is reclassified.
    const party = await service.create(BUSINESS_A, {
      name: 'A',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    expect(party.collectionFrequency).toBe('DAILY');
  });

  it('keeps a mixed set distinct rather than collapsing to DAILY', async () => {
    await service.create(BUSINESS_A, { name: 'A', type: 'CUSTOMER', collectionFrequency: 'DAILY' } as CreatePartyDto);
    await service.create(BUSINESS_A, { name: 'B', type: 'CUSTOMER', collectionFrequency: 'WEEKLY' } as CreatePartyDto);
    await service.create(BUSINESS_A, { name: 'C', type: 'CUSTOMER', collectionFrequency: 'MONTHLY' } as CreatePartyDto);

    expect(rows.map((r) => r.collectionFrequency)).toEqual([
      'DAILY',
      'WEEKLY',
      'MONTHLY',
    ]);
  });

  // ─── Reclassification endpoint ──────────────────────────────

  it('updates an existing party to a new bucket', async () => {
    const created = await service.create(BUSINESS_A, {
      id: '44444444-4444-4444-8444-444444444444',
      name: 'A',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    const updated = await service.updateCollectionFrequency(
      created.id,
      BUSINESS_A,
      'WEEKLY',
    );

    expect(updated.collectionFrequency).toBe('WEEKLY');
  });

  it('is idempotent, so an Outbox retry converges', async () => {
    const created = await service.create(BUSINESS_A, {
      id: '55555555-5555-4555-8555-555555555555',
      name: 'A',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    await service.updateCollectionFrequency(created.id, BUSINESS_A, 'MONTHLY');
    const second = await service.updateCollectionFrequency(created.id, BUSINESS_A, 'MONTHLY');

    expect(second.collectionFrequency).toBe('MONTHLY');
    expect(rows).toHaveLength(1);
  });

  it('will not reclassify a party belonging to another business', async () => {
    const created = await service.create(BUSINESS_A, {
      id: '66666666-6666-4666-8666-666666666666',
      name: 'A',
      type: 'CUSTOMER',
      collectionFrequency: 'WEEKLY',
    } as CreatePartyDto);

    await expect(
      service.updateCollectionFrequency(created.id, BUSINESS_B, 'DAILY'),
    ).rejects.toThrow();

    // Tenant isolation holds: the original classification is untouched.
    expect(rows[0].collectionFrequency).toBe('WEEKLY');
  });

  it('changes only the classification, never the balance', async () => {
    const created = await service.create(BUSINESS_A, {
      id: '77777777-7777-4777-8777-777777777777',
      name: 'A',
      type: 'CUSTOMER',
    } as CreatePartyDto);
    const balanceBefore = created.currentBalance;

    const updated = await service.updateCollectionFrequency(created.id, BUSINESS_A, 'MONTHLY');

    expect(updated.currentBalance).toBe(balanceBefore);
    expect(updated.name).toBe('A');
  });
});
