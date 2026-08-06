import { ConflictException } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import { PartyService } from './party.service';
import { Party } from './party.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * Client-supplied primary keys on party create.
 *
 * The Android app is offline-first and mints party UUIDs locally. Before this,
 * the server generated its own id and the client discarded the response, so the
 * two never agreed and every later ledger write referenced a party the server
 * had never seen.
 */
describe('PartyService — client-supplied ids', () => {
  let service: PartyService;
  let rows: Party[];
  let sendSms: jest.Mock;

  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';
  const CLIENT_ID = '22222222-2222-4222-8222-222222222222';

  const dto = {
    name: 'Rahul Traders',
    phone: '+919876543210',
    type: 'CUSTOMER' as const,
  };

  beforeEach(async () => {
    rows = [];
    sendSms = jest.fn().mockResolvedValue(true);

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        PartyService,
        { provide: SmsService, useValue: { sendSms } },
        {
          provide: getRepositoryToken(Party),
          useValue: {
            create: (obj: Partial<Party>) => Object.assign(new Party(), obj),
            save: jest.fn(async (p: Party) => {
              p.id = p.id ?? `server-generated-${rows.length}`;
              rows.push(p);
              return p;
            }),
            findOneBy: async (where: any) =>
              rows.find((r) =>
                Object.entries(where).every(([k, v]) => (r as any)[k] === v),
              ) ?? null,
          },
        },
      ],
    }).compile();

    service = moduleRef.get(PartyService);
  });

  it('stores the party under the id the client supplied', async () => {
    const party = await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(party.id).toBe(CLIENT_ID);
    expect(rows).toHaveLength(1);
  });

  it('still generates an id when the client omits one', async () => {
    const party = await service.create(BUSINESS_A, dto);

    expect(party.id).toBeDefined();
    expect(rows).toHaveLength(1);
  });

  it('treats a replayed id as a no-op instead of duplicating', async () => {
    const first = await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    const replay = await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(replay.id).toBe(first.id);
    expect(rows).toHaveLength(1);
  });

  it('does not re-send the welcome SMS on replay', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(sendSms).toHaveBeenCalledTimes(1);
  });

  it('ignores a replay that tries to change the stored values', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });
    const replay = await service.create(BUSINESS_A, {
      ...dto,
      id: CLIENT_ID,
      name: 'Overwritten Name',
    });

    // Create is create, not upsert — the stored row wins.
    expect(replay.name).toBe('Rahul Traders');
    expect(rows).toHaveLength(1);
  });

  it('rejects an id already held by another business, without leaking it', async () => {
    await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    const attempt = service.create(BUSINESS_B, { ...dto, id: CLIENT_ID });

    await expect(attempt).rejects.toBeInstanceOf(ConflictException);
    await expect(attempt).rejects.toThrow('Party id is already in use');
    // Nothing about the other tenant's row is disclosed.
    await expect(attempt).rejects.not.toThrow(/Rahul Traders/);
    expect(rows).toHaveLength(1);
  });

  it('recovers from a concurrent insert losing the unique-violation race', async () => {
    const repo = (service as any).partyRepository;
    repo.save.mockImplementationOnce(async () => {
      // Simulate the row landing between our existence check and our insert.
      rows.push(
        Object.assign(new Party(), {
          id: CLIENT_ID,
          businessId: BUSINESS_A,
          ...dto,
        }),
      );
      throw Object.assign(new Error('duplicate key'), { code: '23505' });
    });

    const party = await service.create(BUSINESS_A, { ...dto, id: CLIENT_ID });

    expect(party.id).toBe(CLIENT_ID);
    expect(rows).toHaveLength(1);
  });
});
