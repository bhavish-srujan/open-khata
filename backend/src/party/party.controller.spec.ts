import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { PartyController } from './party.controller';
import { PartyService } from './party.service';
import { Party } from './party.entity';
import { Business } from '../business/business.entity';
import { SmsService } from '../notifications/sms/sms.service';
import { User, UserRole } from '../users/user.entity';
import { BusinessMember } from '../business/business-member.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

/**
 * DELETE /api/v1/parties/:id, over real HTTP through the real guard stack.
 *
 * Deletion cascades away every bill, ledger entry and balance-history row for
 * the party (PartyService.delete), so it was restricted to OWNER/MANAGER —
 * previously any authenticated business member, including CASHIER, could
 * delete a party outright. These tests pin the fix and confirm the existing
 * business-isolation check (a party from another business is not found) is
 * unchanged.
 */
describe('DELETE /api/v1/parties/:id', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const BIZ_A = '11111111-1111-4111-8111-111111111111';
  const BIZ_B = '22222222-2222-4222-8222-222222222222';

  const OWNER_USER = { id: 'user-owner', sessionVersion: 1 };
  const MANAGER_USER = { id: 'user-manager', sessionVersion: 1 };
  const CASHIER_USER = { id: 'user-cashier', sessionVersion: 1 };

  const memberships = [
    { businessId: BIZ_A, userId: OWNER_USER.id, role: UserRole.OWNER },
    { businessId: BIZ_A, userId: MANAGER_USER.id, role: UserRole.MANAGER },
    { businessId: BIZ_A, userId: CASHIER_USER.id, role: UserRole.CASHIER },
  ];

  let rows: Party[];

  const seedParty = (id: string, businessId: string) => {
    const party = Object.assign(new Party(), {
      id,
      businessId,
      name: 'Rahul Traders',
      phone: '+919876500001',
      type: 'CUSTOMER',
      currentBalance: 0,
    });
    rows.push(party);
    return party;
  };

  beforeEach(async () => {
    rows = [];

    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [PartyController],
      providers: [
        PartyService,
        {
          provide: SmsService,
          useValue: { sendSms: jest.fn().mockResolvedValue(true) },
        },
        {
          provide: DataSource,
          useValue: {
            transaction: async (cb: any) =>
              cb({
                createQueryBuilder: (Entity: any) => {
                  const builder: any = {
                    setLock: (_mode: string) => builder,
                    where: (_sql: string, params: any) => ({
                      getOne: async () => {
                        if (Entity !== Party) return null;
                        return (
                          rows.find(
                            (r) =>
                              r.id === params.id &&
                              r.businessId === params.businessId,
                          ) ?? null
                        );
                      },
                    }),
                  };
                  return builder;
                },
                find: async () => [],
                delete: async () => undefined,
                remove: async () => undefined,
              }),
          },
        },
        {
          provide: getRepositoryToken(Business),
          useValue: { findOne: async () => null },
        },
        {
          provide: getRepositoryToken(Party),
          useValue: {
            findOneBy: jest.fn(
              async (where: any) =>
                rows.find((r) =>
                  Object.entries(where).every(([k, v]) => (r as any)[k] === v),
                ) ?? null,
            ),
          },
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

  const del = (id: string, token: string) =>
    request(app.getHttpServer())
      .delete(`/api/v1/parties/${id}`)
      .set('Authorization', `Bearer ${token}`);

  it('OWNER can delete a party in their business', async () => {
    const party = seedParty('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', BIZ_A);

    await del(party.id, tokenFor(OWNER_USER, BIZ_A)).expect(204);
  });

  it('MANAGER can delete a party in their business', async () => {
    const party = seedParty('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', BIZ_A);

    await del(party.id, tokenFor(MANAGER_USER, BIZ_A)).expect(204);
  });

  it('CASHIER is refused with 403', async () => {
    const party = seedParty('cccccccc-cccc-4ccc-8ccc-cccccccccccc', BIZ_A);

    await del(party.id, tokenFor(CASHIER_USER, BIZ_A)).expect(403);
  });

  it('rejects deleting a party belonging to another business, even for OWNER', async () => {
    const party = seedParty('dddddddd-dddd-4ddd-8ddd-dddddddddddd', BIZ_B);

    await del(party.id, tokenFor(OWNER_USER, BIZ_A)).expect(404);
  });
});
