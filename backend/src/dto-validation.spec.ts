import { INestApplication, ValidationPipe } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { PartyController } from './party/party.controller';
import { PartyService } from './party/party.service';
import { LedgerController } from './ledger/ledger.controller';
import { LedgerService } from './ledger/ledger.service';
import { JwtAuthGuard } from './auth/jwt-auth.guard';

/**
 * Exercises the real controllers behind the same global ValidationPipe that
 * main.ts installs, over real HTTP. Services and the auth guard are stubbed, so
 * this needs no database, no JWT secret and sends no SMS.
 *
 * Regression guard for: DTOs carrying no class-validator decorators, which makes
 * `whitelist` strip every property and `forbidNonWhitelisted` reject every request.
 */
describe('POST body validation (parties + ledger)', () => {
  let app: INestApplication;

  const BUSINESS_ID = '11111111-1111-4111-8111-111111111111';
  const PARTY_ID = '22222222-2222-4222-8222-222222222222';

  // Captures what the controller actually forwarded, so we can assert that
  // whitelisting did not silently drop fields the service depends on.
  const created: any[] = [];
  // The businessId each controller resolved, to prove it comes from the token.
  const seenBusinessIds: string[] = [];
  /** Claim the stubbed guard puts on req.user; undefined means "token has none". */
  let tokenBusinessId: string | undefined = BUSINESS_ID;

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      controllers: [PartyController, LedgerController],
      providers: [
        {
          provide: PartyService,
          useValue: {
            create: jest.fn((businessId, dto) => {
              seenBusinessIds.push(businessId);
              created.push(dto);
              return Promise.resolve({ id: PARTY_ID, businessId, ...dto });
            }),
            findAll: jest.fn((businessId) => {
              seenBusinessIds.push(businessId);
              return Promise.resolve([]);
            }),
          },
        },
        {
          provide: LedgerService,
          useValue: {
            create: jest.fn((businessId, dto) => {
              seenBusinessIds.push(businessId);
              created.push(dto);
              return Promise.resolve({ id: 'entry-1', businessId, ...dto });
            }),
          },
        },
      ],
    })
      // Bypass auth; inject whatever claim the current test wants on req.user.
      .overrideGuard(JwtAuthGuard)
      .useValue({
        canActivate: (ctx: any) => {
          ctx.switchToHttp().getRequest().user = tokenBusinessId
            ? { businessId: tokenBusinessId }
            : {};
          return true;
        },
      })
      .compile();

    app = moduleRef.createNestApplication();
    // Identical to main.ts
    app.useGlobalPipes(
      new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }),
    );
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(() => {
    created.length = 0;
    seenBusinessIds.length = 0;
    tokenBusinessId = BUSINESS_ID;
  });

  // The controllers used to accept `req.user.businessId || headers['x-business-id']`,
  // so a caller could name any business they liked if a token ever lacked the claim.
  describe('business scoping comes from the token, not a header', () => {
    const HOSTILE = '00000000-0000-4000-8000-0000deadbeef';

    it('ignores x-business-id on party create', async () => {
      await request(app.getHttpServer())
        .post('/api/v1/parties')
        .set('x-business-id', HOSTILE)
        .send({ name: 'Rahul Traders', phone: '+919876543210', type: 'CUSTOMER' })
        .expect(201);

      expect(seenBusinessIds).toEqual([BUSINESS_ID]);
    });

    it('ignores x-business-id on ledger create', async () => {
      await request(app.getHttpServer())
        .post('/api/v1/ledger')
        .set('x-business-id', HOSTILE)
        .send({ partyId: PARTY_ID, amount: 100, type: 'GAVE' })
        .expect(201);

      expect(seenBusinessIds).toEqual([BUSINESS_ID]);
    });

    // The decisive case. While the header fallback existed, a token with no
    // businessId claim would silently adopt whatever the caller sent, letting
    // them write into a business of their choosing. It must now be refused.
    describe('when the token carries no businessId', () => {
      beforeEach(() => {
        tokenBusinessId = undefined;
      });

      it('refuses party create rather than trusting the header', async () => {
        await request(app.getHttpServer())
          .post('/api/v1/parties')
          .set('x-business-id', HOSTILE)
          .send({ name: 'Rahul Traders', phone: '+919876543210', type: 'CUSTOMER' })
          .expect(400);

        expect(seenBusinessIds).toEqual([]);
      });

      it('refuses ledger create rather than trusting the header', async () => {
        await request(app.getHttpServer())
          .post('/api/v1/ledger')
          .set('x-business-id', HOSTILE)
          .send({ partyId: PARTY_ID, amount: 100, type: 'GAVE' })
          .expect(400);

        expect(seenBusinessIds).toEqual([]);
      });

      it('refuses reads too', async () => {
        await request(app.getHttpServer())
          .get('/api/v1/parties')
          .set('x-business-id', HOSTILE)
          .expect(400);
      });
    });
  });

  describe('POST /api/v1/parties', () => {
    // Exactly what the Android CreatePartyRequest sends today.
    const validBody = {
      name: 'Rahul Traders',
      phone: '+919876543210',
      type: 'CUSTOMER',
      gstin: '29ABCDE1234F1Z5',
      businessId: 'biz_1',
    };

    it('accepts the real Android client payload', async () => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/parties')
        .send(validBody);

      expect(res.status).toBe(201);
    });

    it('does not strip fields the service needs', async () => {
      await request(app.getHttpServer())
        .post('/api/v1/parties')
        .send(validBody)
        .expect(201);

      expect(created[0]).toMatchObject({
        name: 'Rahul Traders',
        phone: '+919876543210',
        type: 'CUSTOMER',
        gstin: '29ABCDE1234F1Z5',
      });
    });

    it.each([
      ['missing name', { ...validBody, name: undefined }, 'name'],
      ['empty name', { ...validBody, name: '' }, 'name'],
      ['bad phone', { ...validBody, phone: 'not-a-phone' }, 'phone'],
      ['bad type', { ...validBody, type: 'VENDOR' }, 'type'],
      ['bad pincode', { ...validBody, pincode: '12' }, 'pincode'],
    ])('rejects %s with a useful message', async (_label, body, field) => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/parties')
        .send(body);

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });

    it('rejects genuinely unknown properties', async () => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/parties')
        .send({ ...validBody, isAdmin: true });

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain('isAdmin');
    });
  });

  describe('POST /api/v1/ledger', () => {
    // Exactly what the Android CreateLedgerEntryRequest sends today.
    const validBody = {
      partyId: PARTY_ID,
      amount: 500.5,
      type: 'GAVE',
      note: 'Opening balance',
      paymentMethod: 'Cash',
      source: 'MANUAL',
    };

    it('accepts the real Android client payload', async () => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/ledger')
        .send(validBody);

      expect(res.status).toBe(201);
    });

    it('preserves source=OCR instead of whitelisting it away', async () => {
      await request(app.getHttpServer())
        .post('/api/v1/ledger')
        .send({ ...validBody, source: 'OCR' })
        .expect(201);

      expect(created[0].source).toBe('OCR');
    });

    it.each([
      ['non-uuid partyId', { ...validBody, partyId: 'party-abc' }, 'partyId'],
      ['negative amount', { ...validBody, amount: -10 }, 'amount'],
      ['zero amount', { ...validBody, amount: 0 }, 'amount'],
      ['over-precise amount', { ...validBody, amount: 1.239 }, 'amount'],
      ['amount overflowing numeric(14,2)', { ...validBody, amount: 1e15 }, 'amount'],
      ['bad type', { ...validBody, type: 'PAID' }, 'type'],
      ['bad source', { ...validBody, source: 'IMPORTED' }, 'source'],
    ])('rejects %s with a useful message', async (_label, body, field) => {
      const res = await request(app.getHttpServer())
        .post('/api/v1/ledger')
        .send(body);

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });
  });
});
