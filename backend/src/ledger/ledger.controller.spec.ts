import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { LedgerController } from './ledger.controller';
import { LedgerService } from './ledger.service';
import { LedgerEntry } from './ledger-entry.entity';
import { BalanceHistory } from './balance-history.entity';
import { Party } from '../party/party.entity';
import { SmsService } from '../notifications/sms/sms.service';
import { User, UserRole } from '../users/user.entity';
import { BusinessMember } from '../business/business-member.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

/**
 * PATCH/DELETE /api/v1/ledger/:id, over real HTTP through the real guard
 * stack.
 *
 * Editing or deleting an entry adjusts a party's running balance, so both
 * were restricted to OWNER/MANAGER — previously any authenticated business
 * member, including CASHIER, could alter or remove any entry after the fact.
 * Business-isolation logic is unchanged and already covered by
 * ledger.service.spec.ts; these tests cover only the new role gate, using the
 * real LedgerController + LedgerService with a stubbed EntityManager, the
 * same shape ledger.service.spec.ts uses.
 */
describe('PATCH/DELETE /api/v1/ledger/:id', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const BIZ_A = '11111111-1111-4111-8111-111111111111';
  const PARTY_ID = '33333333-3333-4333-8333-333333333333';

  const OWNER_USER = { id: 'user-owner', sessionVersion: 1 };
  const MANAGER_USER = { id: 'user-manager', sessionVersion: 1 };
  const CASHIER_USER = { id: 'user-cashier', sessionVersion: 1 };

  const memberships = [
    { businessId: BIZ_A, userId: OWNER_USER.id, role: UserRole.OWNER },
    { businessId: BIZ_A, userId: MANAGER_USER.id, role: UserRole.MANAGER },
    { businessId: BIZ_A, userId: CASHIER_USER.id, role: UserRole.CASHIER },
  ];

  let party: Party;
  let entries: LedgerEntry[];
  let history: BalanceHistory[];

  const seedEntry = (id: string) => {
    const entry = Object.assign(new LedgerEntry(), {
      id,
      businessId: BIZ_A,
      partyId: PARTY_ID,
      amount: 500,
      type: 'GAVE',
      note: 'original',
    });
    entries.push(entry);
    return entry;
  };

  beforeEach(async () => {
    entries = [];
    history = [];
    party = Object.assign(new Party(), {
      id: PARTY_ID,
      businessId: BIZ_A,
      type: 'CUSTOMER',
      phone: '+919876543210',
      currentBalance: 500,
    });

    // Same stand-in EntityManager shape as ledger.service.spec.ts.
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
      findOne: async (Entity: any, { where }: any) => {
        if (Entity !== LedgerEntry) return null;
        return (
          entries.find((e) =>
            Object.entries(where).every(([k, v]) => (e as any)[k] === v),
          ) ?? null
        );
      },
      create: (Entity: any, obj: any) => Object.assign(new Entity(), obj),
      save: async (entity: any) => {
        if (entity instanceof BalanceHistory) history.push(entity);
        return entity;
      },
      update: async () => undefined,
      remove: async (entity: any) => {
        entries = entries.filter((e) => e.id !== entity.id);
      },
    };

    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [LedgerController],
      providers: [
        LedgerService,
        { provide: getRepositoryToken(LedgerEntry), useValue: {} },
        { provide: getRepositoryToken(Party), useValue: {} },
        {
          provide: SmsService,
          useValue: (() => { const f = jest.fn().mockResolvedValue(true); return { sendSms: f, sendOtpSms: f, sendTransactionalSms: f }; })(),
        },
        {
          provide: DataSource,
          useValue: { transaction: (cb: any) => cb(manager) },
        },
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(
              async ({ id }: any) =>
                [OWNER_USER, MANAGER_USER, CASHIER_USER].find(
                  (u) => u.id === id,
                ) ?? null,
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
        JwtAuthGuard,
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterEach(async () => {
    await app.close();
  });

  const tokenFor = (user: { id: string; sessionVersion: number }) =>
    jwt.sign({
      sub: user.id,
      businessId: BIZ_A,
      role: UserRole.OWNER, // overwritten by the guard from real membership
      sessionVersion: user.sessionVersion,
    });

  const patch = (id: string, token: string, body: any) =>
    request(app.getHttpServer())
      .patch(`/api/v1/ledger/${id}`)
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  const del = (id: string, token: string) =>
    request(app.getHttpServer())
      .delete(`/api/v1/ledger/${id}`)
      .set('Authorization', `Bearer ${token}`);

  describe('PATCH', () => {
    it('OWNER can update an entry', async () => {
      const entry = seedEntry('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');

      await patch(entry.id, tokenFor(OWNER_USER), { amount: 800 }).expect(200);

      expect(entry.amount).toBe(800);
    });

    it('MANAGER can update an entry', async () => {
      const entry = seedEntry('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');

      await patch(entry.id, tokenFor(MANAGER_USER), { amount: 800 }).expect(
        200,
      );

      expect(entry.amount).toBe(800);
    });

    it('CASHIER is refused with 403, entry left unchanged', async () => {
      const entry = seedEntry('cccccccc-cccc-4ccc-8ccc-cccccccccccc');

      await patch(entry.id, tokenFor(CASHIER_USER), { amount: 800 }).expect(
        403,
      );

      expect(entry.amount).toBe(500);
    });
  });

  describe('DELETE', () => {
    it('OWNER can delete an entry', async () => {
      const entry = seedEntry('dddddddd-dddd-4ddd-8ddd-dddddddddddd');

      await del(entry.id, tokenFor(OWNER_USER)).expect(204);

      expect(entries).toHaveLength(0);
    });

    it('MANAGER can delete an entry', async () => {
      const entry = seedEntry('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee');

      await del(entry.id, tokenFor(MANAGER_USER)).expect(204);

      expect(entries).toHaveLength(0);
    });

    it('CASHIER is refused with 403, entry left in place', async () => {
      const entry = seedEntry('ffffffff-ffff-4fff-8fff-ffffffffffff');

      await del(entry.id, tokenFor(CASHIER_USER)).expect(403);

      expect(entries).toHaveLength(1);
    });
  });
});
