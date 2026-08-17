import { INestApplication, ValidationPipe } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { BillsController } from './bills.controller';
import { BillsService } from './bills.service';
import { Bill } from './entities/bill.entity';
import { Party } from '../party/party.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

/**
 * Bill creation.
 *
 * CreateBillDto carried no class-validator metadata, so under the global pipe's
 * whitelist every property was non-whitelisted and forbidNonWhitelisted
 * rejected every request. Creating a bill was impossible — the endpoint
 * answered 400 to perfectly valid input.
 *
 * BillsService also never checked that partyId belonged to the caller's
 * business, so a bill could be raised against another business's customer.
 */
describe('bills', () => {
  let app: INestApplication;

  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const PARTY_A = '22222222-2222-4222-8222-222222222222';
  const PARTY_OTHER_BUSINESS = '33333333-3333-4333-8333-333333333333';

  const saved: any[] = [];

  const parties = [
    { id: PARTY_A, businessId: BUSINESS_A, name: 'Rahul Traders' },
    {
      id: PARTY_OTHER_BUSINESS,
      businessId: 'some-other-business',
      name: 'Not Yours',
    },
  ];

  const validBill = () => ({
    partyId: PARTY_A,
    totalAmount: 1250.5,
    items: [
      { description: 'Basmati Rice 5kg', quantity: 2, price: 480, tax: 5 },
    ],
    isGst: true,
  });

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      controllers: [BillsController],
      providers: [
        BillsService,
        {
          provide: getRepositoryToken(Bill),
          useValue: {
            create: (o: any) => o,
            save: jest.fn(
              async (b: any) => (saved.push(b), { id: 'bill-1', ...b }),
            ),
            find: jest.fn(async () => []),
            findOne: jest.fn(async () => null),
          },
        },
        {
          provide: getRepositoryToken(Party),
          useValue: {
            // Matches on whichever keys the query carries, like a real repo.
            findOne: jest.fn(
              async ({ where }: any) =>
                parties.find((p) =>
                  Object.entries(where).every(([k, v]) => (p as any)[k] === v),
                ) ?? null,
            ),
          },
        },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({
        canActivate: (c: any) => {
          c.switchToHttp().getRequest().user = {
            sub: 'user-1',
            businessId: BUSINESS_A,
            role: 'OWNER',
          };
          return true;
        },
      })
      .compile();

    app = moduleRef.createNestApplication();
    // Identical to main.ts — note there is no transform: true.
    app.useGlobalPipes(
      new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }),
    );
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(() => {
    saved.length = 0;
  });

  describe('a valid bill can finally be created', () => {
    it('accepts a well-formed bill', async () => {
      await request(app.getHttpServer())
        .post('/bills')
        .send(validBill())
        .expect(201);
    });

    it('persists the line items and stamps the business from the token', async () => {
      await request(app.getHttpServer())
        .post('/bills')
        .send(validBill())
        .expect(201);

      expect(saved[0]).toMatchObject({
        partyId: PARTY_A,
        totalAmount: 1250.5,
        businessId: BUSINESS_A,
      });
      expect(saved[0].items).toHaveLength(1);
      expect(saved[0].items[0].description).toBe('Basmati Rice 5kg');
    });

    it('treats isGst as optional', async () => {
      const { isGst, ...withoutGst } = validBill();

      await request(app.getHttpServer())
        .post('/bills')
        .send(withoutGst)
        .expect(201);
    });
  });

  describe('top-level validation', () => {
    it.each([
      ['non-uuid id', { id: 'not-a-uuid' }, 'id'],
      ['non-uuid partyId', { partyId: 'party-abc' }, 'partyId'],
      ['negative total', { totalAmount: -1 }, 'totalAmount'],
      ['zero total', { totalAmount: 0 }, 'totalAmount'],
      ['over-precise total', { totalAmount: 10.005 }, 'totalAmount'],
      ['total beyond numeric(10,2)', { totalAmount: 1e9 }, 'totalAmount'],
      ['total as a string', { totalAmount: '100' }, 'totalAmount'],
      ['empty items', { items: [] }, 'items'],
      ['items not an array', { items: 'nope' }, 'items'],
      ['non-boolean isGst', { isGst: 'yes' }, 'isGst'],
    ])('rejects %s', async (_label, override, field) => {
      const res = await request(app.getHttpServer())
        .post('/bills')
        .send({ ...validBill(), ...override });

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });

    it.each([
      ['businessId', { businessId: 'someone-elses' }],
      ['ledgerEntryId', { ledgerEntryId: 'x' }],
      ['receiptUrl', { receiptUrl: '/uploads/receipts/x' }],
    ])('rejects the server-controlled field %s', async (field, override) => {
      const res = await request(app.getHttpServer())
        .post('/bills')
        .send({ ...validBill(), ...override });

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });
  });

  /**
   * Nested validation relies on class-transformer running inside the pipe.
   * ValidationPipe builds the DTO instance with plainToInstance regardless of
   * the `transform` option, so @Type is honoured — but that is an assumption
   * worth proving rather than trusting, since main.ts does not set transform.
   */
  describe('line item validation actually runs', () => {
    const withItem = (item: any) => ({ ...validBill(), items: [item] });
    const goodItem = { description: 'Rice', quantity: 2, price: 480 };

    it.each([
      [
        'missing description',
        { ...goodItem, description: undefined },
        'description',
      ],
      ['blank description', { ...goodItem, description: '' }, 'description'],
      ['zero quantity', { ...goodItem, quantity: 0 }, 'quantity'],
      ['negative quantity', { ...goodItem, quantity: -1 }, 'quantity'],
      ['negative price', { ...goodItem, price: -5 }, 'price'],
      ['over-precise price', { ...goodItem, price: 1.239 }, 'price'],
      ['tax above 100 percent', { ...goodItem, tax: 150 }, 'tax'],
      ['unknown field on the item', { ...goodItem, isAdmin: true }, 'isAdmin'],
    ])('rejects a line item with %s', async (_label, item, field) => {
      const res = await request(app.getHttpServer())
        .post('/bills')
        .send(withItem(item));

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });

    it('rejects a bad item even when another item is valid', async () => {
      const res = await request(app.getHttpServer())
        .post('/bills')
        .send({
          ...validBill(),
          items: [goodItem, { ...goodItem, price: -1 }],
        });

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain('price');
    });
  });

  describe('tenancy', () => {
    it("refuses a bill against another business's party", async () => {
      const res = await request(app.getHttpServer())
        .post('/bills')
        .send({ ...validBill(), partyId: PARTY_OTHER_BUSINESS });

      expect(res.status).toBe(404);
      expect(saved).toHaveLength(0);
    });

    it('refuses a bill against a party that does not exist', async () => {
      const res = await request(app.getHttpServer())
        .post('/bills')
        .send({
          ...validBill(),
          partyId: '44444444-4444-4444-8444-444444444444',
        });

      expect(res.status).toBe(404);
      expect(saved).toHaveLength(0);
    });

    it('scopes the party lookup by business in the query itself', async () => {
      const repo = app.get(getRepositoryToken(Party));
      repo.findOne.mockClear();

      await request(app.getHttpServer()).post('/bills').send(validBill());

      expect(repo.findOne).toHaveBeenCalledWith({
        where: { id: PARTY_A, businessId: BUSINESS_A },
      });
    });
  });
});
