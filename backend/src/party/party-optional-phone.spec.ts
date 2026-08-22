import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { CreatePartyDto } from './dto/create-party.dto';
import { PartyService, normalizePartyName } from './party.service';
import { Party } from './party.entity';
import { Business } from '../business/business.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * A party is identified by its name; a phone number is optional contact detail.
 *
 * Regression cover for a client/server contract break: the Android app allows
 * creating a customer with no phone, while this DTO still required one. The
 * create returned 400, the Outbox classified that as a permanent failure, and
 * the customer stayed on the device forever without ever syncing.
 */
describe('PartyService — phone is optional', () => {
  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';

  let service: PartyService;
  let rows: Party[];
  let sendTransactionalSms: jest.Mock;

  /** Runs the DTO through the same validation the global ValidationPipe applies. */
  const validateDto = async (payload: Record<string, unknown>) => {
    const dto = plainToInstance(CreatePartyDto, payload);
    const errors = await validate(dto, {
      whitelist: true,
      forbidNonWhitelisted: true,
    });
    return errors.map((e) => e.property);
  };

  beforeEach(async () => {
    rows = [];
    sendTransactionalSms = jest.fn().mockResolvedValue(true);

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        PartyService,
        {
          provide: getRepositoryToken(Party),
          useValue: {
            create: (o: any) => Object.assign(new Party(), o),
            save: jest.fn(async (p: any) => {
              const stored = { ...p, id: p.id ?? `generated-${rows.length}` };
              rows.push(stored as Party);
              return stored;
            }),
            findOneBy: jest.fn(async (where: any) =>
              rows.find((r) => r.id === where.id) ?? null,
            ),
          },
        },
        {
          provide: getRepositoryToken(Business),
          useValue: { findOne: async () => ({ id: BUSINESS_A, name: 'Test Shop' }) },
        },
        { provide: SmsService, useValue: { sendTransactionalSms } },
        { provide: DataSource, useValue: { transaction: jest.fn() } },
      ],
    }).compile();

    service = moduleRef.get(PartyService);
  });

  // ─── DTO validation ─────────────────────────────────────────

  it('accepts a create with a valid phone, exactly as before', async () => {
    expect(
      await validateDto({
        name: 'Ramesh',
        phone: '9876543210',
        type: 'CUSTOMER',
      }),
    ).toEqual([]);
  });

  it('accepts a create with the phone omitted entirely', async () => {
    expect(await validateDto({ name: 'Ramesh', type: 'CUSTOMER' })).toEqual([]);
  });

  it('accepts a create with an empty-string phone', async () => {
    // The Android client sends "" rather than omitting the key.
    expect(
      await validateDto({ name: 'Ramesh', phone: '', type: 'CUSTOMER' }),
    ).toEqual([]);
  });

  it('still rejects a malformed phone when one IS supplied', async () => {
    // Relaxing "required" must not also relax "valid".
    expect(
      await validateDto({ name: 'Ramesh', phone: 'not-a-phone', type: 'CUSTOMER' }),
    ).toContain('phone');
  });

  it('still requires the name — only phone became optional', async () => {
    expect(await validateDto({ phone: '9876543210', type: 'CUSTOMER' })).toContain(
      'name',
    );
    expect(
      await validateDto({ name: '   ', phone: '9876543210', type: 'CUSTOMER' }),
    ).toContain('name');
  });

  it('still rejects an invalid party type', async () => {
    expect(
      await validateDto({ name: 'Ramesh', phone: '9876543210', type: 'BANANA' }),
    ).toContain('type');
  });

  // ─── Service behaviour ──────────────────────────────────────

  it('persists a party created without a phone', async () => {
    const party = await service.create(BUSINESS_A, {
      name: 'Ramesh',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    expect(party.name).toBe('RAMESH');
    expect(rows).toHaveLength(1);
  });

  it('stores an absent phone as blank, never as a fabricated placeholder', async () => {
    const party = await service.create(BUSINESS_A, {
      name: 'Ramesh',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    // Blank satisfies the NOT NULL column without inventing a number that
    // downstream code would treat as real and try to message or dial.
    expect(party.phone).toBe('');
    expect(party.phone).not.toContain('0000000000');
  });

  it('sends no welcome SMS when there is no phone to send it to', async () => {
    await service.create(BUSINESS_A, {
      name: 'Ramesh',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    expect(sendTransactionalSms).not.toHaveBeenCalled();
  });

  it('preserves a supplied phone number exactly', async () => {
    const party = await service.create(BUSINESS_A, {
      name: 'Ramesh',
      phone: '+91 98765 43210',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    expect(party.phone).toBe('+91 98765 43210');
  });

  it('scopes a phoneless party to the caller\'s business', async () => {
    const party = await service.create(BUSINESS_B, {
      name: 'Ramesh',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    // Tenant isolation is unaffected by phone optionality.
    expect(party.businessId).toBe(BUSINESS_B);
  });

  // ─── Name normalisation ─────────────────────────────────────

  it('normalises every casing variant to one canonical name', () => {
    expect(normalizePartyName('bhavish')).toBe('BHAVISH');
    expect(normalizePartyName('Bhavish')).toBe('BHAVISH');
    expect(normalizePartyName('BHAVISH')).toBe('BHAVISH');
    expect(normalizePartyName('  bHaViSh  ')).toBe('BHAVISH');
  });

  it('collapses internal whitespace so OCR spacing does not fork a record', () => {
    expect(normalizePartyName('RAVI  KUMAR')).toBe('RAVI KUMAR');
  });

  it('does NOT merge genuinely different names', () => {
    // A shared prefix is not identity — these stay distinct records.
    expect(normalizePartyName('RAHUL')).not.toBe(normalizePartyName('RAHUL KUMAR'));
    expect(normalizePartyName('RAJESH')).not.toBe(normalizePartyName('RAJENDRA'));
  });

  it('stores the canonical name on create', async () => {
    const party = await service.create(BUSINESS_A, {
      name: 'bhavish',
      type: 'CUSTOMER',
    } as CreatePartyDto);

    expect(party.name).toBe('BHAVISH');
  });
});
