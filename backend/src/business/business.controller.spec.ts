import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { BusinessController } from './business.controller';
import { BusinessService } from './business.service';
import { Business } from './business.entity';
import { BusinessMember } from './business-member.entity';
import { User, UserRole } from '../users/user.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

/**
 * GET /businesses, over real HTTP through the real guard stack.
 *
 * The Android client hides OWNER/MANAGER-only actions (party deletion,
 * ledger edit/delete, staff management) from a CASHIER, and needs to know
 * the caller's own role in each business to do it — the endpoint previously
 * returned the Business rows with no role at all. This pins that the role
 * held in *this* business comes back alongside it, and that it is the
 * membership's role (which can differ per business), not anything global.
 */
describe('GET /businesses', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const OWNER_USER = { id: 'user-owner', sessionVersion: 1 };
  const CASHIER_USER = { id: 'user-cashier', sessionVersion: 1 };

  const BIZ_A = Object.assign(new Business(), {
    id: '11111111-1111-4111-8111-111111111111',
    ownerId: OWNER_USER.id,
    name: 'Shop A',
  });
  const BIZ_B = Object.assign(new Business(), {
    id: '22222222-2222-4222-8222-222222222222',
    ownerId: OWNER_USER.id,
    name: 'Shop B',
  });

  const memberships = [
    {
      businessId: BIZ_A.id,
      userId: OWNER_USER.id,
      role: UserRole.OWNER,
      business: BIZ_A,
      createdAt: new Date(0),
    },
    {
      businessId: BIZ_B.id,
      userId: CASHIER_USER.id,
      role: UserRole.CASHIER,
      business: BIZ_B,
      createdAt: new Date(0),
    },
  ];

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [BusinessController],
      providers: [
        BusinessService,
        {
          provide: DataSource,
          useValue: {
            getRepository: (entity: any) => {
              if (entity !== BusinessMember) return null;
              return {
                find: jest.fn(async ({ where }: any) =>
                  memberships.filter((m) => m.userId === where.userId),
                ),
                findOne: jest.fn(async ({ where }: any) =>
                  memberships.find(
                    (m) =>
                      m.businessId === where.businessId &&
                      m.userId === where.userId,
                  ) ?? null,
                ),
              };
            },
          },
        },
        { provide: getRepositoryToken(Business), useValue: {} },
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
        JwtAuthGuard,
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  const tokenFor = (
    user: { id: string; sessionVersion: number },
    businessId: string,
  ) =>
    jwt.sign({
      sub: user.id,
      businessId,
      role: UserRole.OWNER,
      sessionVersion: user.sessionVersion,
    });

  it("includes the caller's own role alongside each business", async () => {
    const res = await request(app.getHttpServer())
      .get('/businesses')
      .set('Authorization', `Bearer ${tokenFor(OWNER_USER, BIZ_A.id)}`)
      .expect(200);

    expect(res.body).toHaveLength(1);
    expect(res.body[0]).toMatchObject({ id: BIZ_A.id, role: UserRole.OWNER });
  });

  it('reflects the membership role, not a role fixed by the token', async () => {
    // The token itself claims OWNER (as it would for whichever business the
    // user is currently active in elsewhere) — the CASHIER role here must
    // come from this business's own membership row, not from the token.
    const res = await request(app.getHttpServer())
      .get('/businesses')
      .set('Authorization', `Bearer ${tokenFor(CASHIER_USER, BIZ_B.id)}`)
      .expect(200);

    expect(res.body).toHaveLength(1);
    expect(res.body[0]).toMatchObject({ id: BIZ_B.id, role: UserRole.CASHIER });
  });
});

/**
 * GET /businesses/:id.
 *
 * Previously scoped to ownerId alone (the same check update()/delete() use),
 * so a CASHIER fetching the single business they belong to — not owning it —
 * got 404 for their own workplace. findOne() itself was deliberately left
 * owner-only (update()/delete() reuse it, and widening it would let a
 * CASHIER edit or delete the business); this pins the new membership-based
 * read path instead.
 */
describe('GET /businesses/:id', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const OWNER_USER = { id: 'user-owner', sessionVersion: 1 };
  const CASHIER_USER = { id: 'user-cashier', sessionVersion: 1 };
  const OUTSIDER_USER = { id: 'user-outsider', sessionVersion: 1 };

  const BIZ = Object.assign(new Business(), {
    id: '33333333-3333-4333-8333-333333333333',
    ownerId: OWNER_USER.id,
    name: 'Shop C',
  });

  // The outsider needs a business of their own — JwtAuthGuard itself checks
  // the token's businessId against membership before the controller ever
  // runs, so a token naming BIZ (which they don't belong to) would 401 at
  // the guard. Giving them a real membership elsewhere is what makes this an
  // IDOR test against the controller's own check, not a guard rejection.
  const OUTSIDER_BIZ = Object.assign(new Business(), {
    id: '44444444-4444-4444-8444-444444444444',
    ownerId: OUTSIDER_USER.id,
    name: 'Shop D',
  });

  const memberships = [
    {
      businessId: BIZ.id,
      userId: OWNER_USER.id,
      role: UserRole.OWNER,
      business: BIZ,
    },
    {
      businessId: BIZ.id,
      userId: CASHIER_USER.id,
      role: UserRole.CASHIER,
      business: BIZ,
    },
    {
      businessId: OUTSIDER_BIZ.id,
      userId: OUTSIDER_USER.id,
      role: UserRole.OWNER,
      business: OUTSIDER_BIZ,
    },
  ];

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [BusinessController],
      providers: [
        BusinessService,
        {
          provide: DataSource,
          useValue: {
            getRepository: (entity: any) => {
              if (entity !== BusinessMember) return null;
              return {
                findOne: jest.fn(async ({ where }: any) =>
                  memberships.find(
                    (m) =>
                      m.businessId === where.businessId &&
                      m.userId === where.userId,
                  ) ?? null,
                ),
              };
            },
          },
        },
        { provide: getRepositoryToken(Business), useValue: {} },
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(
              async ({ id }: any) =>
                [OWNER_USER, CASHIER_USER, OUTSIDER_USER].find(
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

  afterAll(async () => {
    await app.close();
  });

  const tokenFor = (
    user: { id: string; sessionVersion: number },
    businessId: string = BIZ.id,
  ) =>
    jwt.sign({
      sub: user.id,
      businessId,
      role: UserRole.OWNER,
      sessionVersion: user.sessionVersion,
    });

  it('OWNER reaches their own business', async () => {
    const res = await request(app.getHttpServer())
      .get(`/businesses/${BIZ.id}`)
      .set('Authorization', `Bearer ${tokenFor(OWNER_USER)}`)
      .expect(200);

    expect(res.body).toMatchObject({ id: BIZ.id, role: UserRole.OWNER });
  });

  it('CASHIER reaches the business they are staff of (regression: used to be 404)', async () => {
    const res = await request(app.getHttpServer())
      .get(`/businesses/${BIZ.id}`)
      .set('Authorization', `Bearer ${tokenFor(CASHIER_USER)}`)
      .expect(200);

    expect(res.body).toMatchObject({ id: BIZ.id, role: UserRole.CASHIER });
  });

  it('a non-member is refused with 404, not confirmed to exist', async () => {
    await request(app.getHttpServer())
      .get(`/businesses/${BIZ.id}`)
      .set('Authorization', `Bearer ${tokenFor(OUTSIDER_USER, OUTSIDER_BIZ.id)}`)
      .expect(404);
  });
});
