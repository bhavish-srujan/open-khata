import { INestApplication, ValidationPipe } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { RemindersController } from './reminders.controller';
import { RemindersService } from './reminders.service';
import { SmsService } from '../notifications/sms/sms.service';
import { Party } from '../party/party.entity';
import { User, UserRole } from '../users/user.entity';
import { BusinessMember } from '../business/business-member.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

/**
 * POST /api/v1/reminders/send, exercised over real HTTP through the real
 * guard stack (JwtAuthGuard, RolesGuard, the global ThrottlerGuard) and the
 * real ValidationPipe config main.ts installs.
 *
 * Before the fix, this route took `{ phoneNumber, amount, partyName }`
 * straight from the client with no DTO, no party lookup and no business
 * check — any authenticated user of any business, any role, could make the
 * server send an SMS with attacker-chosen text to any phone number. These
 * tests pin the fix: the client may only name a partyId, and everything else
 * about the message is resolved server-side from that party's own row,
 * scoped to the caller's membership-verified business.
 */
describe('POST /api/v1/reminders/send', () => {
  let app: INestApplication;
  let jwt: JwtService;
  let sendSms: jest.Mock;

  const BIZ_A = '11111111-1111-4111-8111-111111111111';
  const BIZ_B = '22222222-2222-4222-8222-222222222222';
  const BIZ_NO_MEMBERSHIP = '99999999-9999-4999-8999-999999999999';

  const OWNER_USER = { id: 'user-owner', sessionVersion: 1 };
  const CASHIER_USER = { id: 'user-cashier', sessionVersion: 1 };

  const PARTY_A = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    businessId: BIZ_A,
    name: 'Rahul Traders',
    phone: '+919876500001',
    currentBalance: '750.00',
  };
  const PARTY_B = {
    // Same shape, but belongs to a different business than the caller's.
    id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    businessId: BIZ_B,
    name: 'Priya Stores',
    phone: '+919876500002',
    currentBalance: '400.00',
  };
  /** Dedicated to the SMS-failure/retry test, so its cooldown state cannot
   *  leak into any other test. */
  const RETRY_PARTY = {
    id: 'ee000000-eeee-4eee-8eee-eeeeeeeeeeee',
    businessId: BIZ_A,
    name: 'Retry Co',
    phone: '+919876500003',
    currentBalance: '250.00',
  };
  /** Dedicated to the duplicate/replay test, for the same reason. */
  const DUPLICATE_PARTY = {
    id: 'ff000000-ffff-4fff-8fff-ffffffffffff',
    businessId: BIZ_A,
    name: 'Duplicate Co',
    phone: '+919876500004',
    currentBalance: '300.00',
  };

  const parties = [PARTY_A, PARTY_B, RETRY_PARTY, DUPLICATE_PARTY];

  // Owner is a member of BIZ_A only; cashier is a member of BIZ_A with a
  // lesser role. Neither belongs to BIZ_NO_MEMBERSHIP.
  const memberships = [
    { businessId: BIZ_A, userId: OWNER_USER.id, role: UserRole.OWNER },
    { businessId: BIZ_A, userId: CASHIER_USER.id, role: UserRole.CASHIER },
  ];

  const buildRepoProviders = (partyList: typeof parties) => [
    {
      provide: getRepositoryToken(Party),
      useValue: {
        findOneBy: jest.fn(
          async ({ id, businessId }: any) =>
            partyList.find((p) => p.id === id && p.businessId === businessId) ??
            null,
        ),
      },
    },
    {
      provide: getRepositoryToken(User),
      useValue: {
        findOneBy: jest.fn(
          async ({ id }: any) =>
            [OWNER_USER, CASHIER_USER].find((u) => u.id === id) ?? null,
        ),
      },
    },
    {
      provide: getRepositoryToken(BusinessMember),
      useValue: {
        findOneBy: jest.fn(
          async (w: any) =>
            memberships.find(
              (m) => m.businessId === w.businessId && m.userId === w.userId,
            ) ?? null,
        ),
      },
    },
  ];

  beforeAll(async () => {
    sendSms = jest.fn().mockResolvedValue(true);

    const moduleRef: TestingModule = await Test.createTestingModule({
      // No ThrottlerModule/APP_GUARD here: the route carries its own
      // @Throttle(5/min) decorator, and every functional test below shares
      // this one app instance and this one route, so a real ThrottlerGuard
      // would exhaust that budget within the first handful of tests
      // regardless of what limit is configured here. The IP-throttle
      // behaviour itself is proven in its own isolated app below, which is
      // the only place a ThrottlerGuard runs.
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [RemindersController],
      providers: [
        RemindersService,
        {
          provide: SmsService,
          useValue: { sendSms, isConfigured: () => false },
        },
        ...buildRepoProviders(parties),
        JwtAuthGuard,
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    app.useGlobalPipes(
      new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }),
    );
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(() => {
    sendSms.mockClear();
    sendSms.mockResolvedValue(true);
  });

  const tokenFor = (
    user: { id: string; sessionVersion: number },
    businessId: string,
  ) =>
    jwt.sign({
      sub: user.id,
      businessId,
      role: UserRole.OWNER, // overwritten by the guard from real membership
      sessionVersion: user.sessionVersion,
    });

  const send = (body: any, token: string) =>
    request(app.getHttpServer())
      .post('/api/v1/reminders/send')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  it('sends a reminder for a party the caller is authorized for', async () => {
    const res = await send(
      { partyId: PARTY_A.id },
      tokenFor(OWNER_USER, BIZ_A),
    ).expect(200);

    expect(res.body.success).toBe(true);
    // Resolved server-side from the party row, not from anything the client sent.
    expect(sendSms).toHaveBeenCalledWith(
      PARTY_A.phone,
      expect.stringContaining(PARTY_A.name),
    );
    expect(sendSms).toHaveBeenCalledWith(
      expect.any(String),
      expect.stringContaining('750'),
    );
  });

  it('rejects a party belonging to another business', async () => {
    const res = await send(
      { partyId: PARTY_B.id },
      tokenFor(OWNER_USER, BIZ_A),
    );

    expect(res.status).toBe(404);
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('rejects an arbitrary phone number — the field no longer exists on the DTO', async () => {
    const res = await send(
      { phoneNumber: '+911234567890' },
      tokenFor(OWNER_USER, BIZ_A),
    );

    expect(res.status).toBe(400);
    expect(JSON.stringify(res.body.message)).toContain('phoneNumber');
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('rejects a well-formed but unknown party id', async () => {
    const res = await send(
      { partyId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd' },
      tokenFor(OWNER_USER, BIZ_A),
    );

    expect(res.status).toBe(404);
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('rejects a role without send permission (CASHIER)', async () => {
    const res = await send(
      { partyId: PARTY_A.id },
      tokenFor(CASHIER_USER, BIZ_A),
    );

    expect(res.status).toBe(403);
    expect(sendSms).not.toHaveBeenCalled();
  });

  it.each([
    ['missing partyId', {}],
    ['non-UUID partyId', { partyId: 'not-a-uuid' }],
    ['numeric partyId', { partyId: 12345 }],
    ['null body field', { partyId: null }],
  ])('rejects malformed input: %s', async (_label, body) => {
    const res = await send(body, tokenFor(OWNER_USER, BIZ_A));

    expect(res.status).toBe(400);
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('rejects an attempt to manipulate businessId via the body', async () => {
    const res = await send(
      { partyId: PARTY_A.id, businessId: BIZ_B },
      tokenFor(OWNER_USER, BIZ_A),
    );

    expect(res.status).toBe(400);
    expect(JSON.stringify(res.body.message)).toContain('businessId');
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('rejects an attempt to override amount, partyName and phoneNumber', async () => {
    const res = await send(
      {
        partyId: PARTY_A.id,
        amount: 1,
        partyName: 'Someone Else',
        phoneNumber: '+910000000000',
      },
      tokenFor(OWNER_USER, BIZ_A),
    );

    expect(res.status).toBe(400);
    const message = JSON.stringify(res.body.message);
    expect(message).toContain('amount');
    expect(message).toContain('partyName');
    expect(message).toContain('phoneNumber');
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('rejects a caller with no membership in the business named by their own token', async () => {
    const res = await send(
      { partyId: PARTY_A.id },
      tokenFor(OWNER_USER, BIZ_NO_MEMBERSHIP),
    );

    expect(res.status).toBe(401);
    expect(sendSms).not.toHaveBeenCalled();
  });

  it('handles an SMS provider failure without a 500, and allows a retry', async () => {
    sendSms.mockResolvedValueOnce(false);

    const failed = await send(
      { partyId: RETRY_PARTY.id },
      tokenFor(OWNER_USER, BIZ_A),
    );
    expect(failed.status).toBe(400);

    // The failed attempt must not have burned the per-party cooldown.
    const retried = await send(
      { partyId: RETRY_PARTY.id },
      tokenFor(OWNER_USER, BIZ_A),
    );
    expect(retried.status).toBe(200);
  });

  it('rejects a duplicate/replay send for the same party within the cooldown window', async () => {
    const first = await send(
      { partyId: DUPLICATE_PARTY.id },
      tokenFor(OWNER_USER, BIZ_A),
    );
    expect(first.status).toBe(200);

    const second = await send(
      { partyId: DUPLICATE_PARTY.id },
      tokenFor(OWNER_USER, BIZ_A),
    );
    expect(second.status).toBe(429);
    expect(JSON.stringify(second.body.message)).toMatch(/already sent/i);
  });

  /**
   * Isolated in its own app/module so its low throttle limit cannot bleed
   * into (or be exhausted by) the functional tests above, which share one
   * app instance and one cumulative per-IP request count.
   */
  describe('per-IP rate limiting', () => {
    let throttledApp: INestApplication;
    let throttledJwt: JwtService;
    const THROTTLE_LIMIT = 5;
    /** More distinct parties than the limit, so every request would succeed
     *  on its own merits — any rejection is the IP throttle, not the
     *  per-party cooldown. */
    const throttleParties = Array.from(
      { length: THROTTLE_LIMIT + 1 },
      (_, i) => ({
        id: `12000000-1200-4120-8120-12000000000${i}`,
        businessId: BIZ_A,
        name: `Throttle Party ${i}`,
        phone: `+91987651${1000 + i}`,
        currentBalance: '50.00',
      }),
    );

    beforeAll(async () => {
      const throttleSendSms = jest.fn().mockResolvedValue(true);

      const moduleRef: TestingModule = await Test.createTestingModule({
        imports: [
          JwtModule.register({ secret: 'test-secret' }),
          ThrottlerModule.forRoot([
            { name: 'default', ttl: 60_000, limit: THROTTLE_LIMIT },
          ]),
        ],
        controllers: [RemindersController],
        providers: [
          RemindersService,
          {
            provide: SmsService,
            useValue: { sendSms: throttleSendSms, isConfigured: () => false },
          },
          ...buildRepoProviders(throttleParties),
          JwtAuthGuard,
          { provide: APP_GUARD, useClass: ThrottlerGuard },
        ],
      }).compile();

      throttledJwt = moduleRef.get(JwtService);
      throttledApp = moduleRef.createNestApplication();
      throttledApp.useGlobalPipes(
        new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }),
      );
      await throttledApp.init();
    });

    afterAll(async () => {
      await throttledApp.close();
    });

    it('throttles repeated requests past the per-IP limit', async () => {
      const token = throttledJwt.sign({
        sub: OWNER_USER.id,
        businessId: BIZ_A,
        role: UserRole.OWNER,
        sessionVersion: OWNER_USER.sessionVersion,
      });

      const statuses: number[] = [];
      for (const party of throttleParties) {
        const res = await request(throttledApp.getHttpServer())
          .post('/api/v1/reminders/send')
          .set('Authorization', `Bearer ${token}`)
          .send({ partyId: party.id });
        statuses.push(res.status);
      }

      // One more request than the limit: the tail must be throttled.
      expect(statuses).toContain(429);
      expect(statuses.filter((s) => s === 200).length).toBeLessThanOrEqual(
        THROTTLE_LIMIT,
      );
    });
  });
});
