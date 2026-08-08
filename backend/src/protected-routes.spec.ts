import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { BusinessMember } from './business/business-member.entity';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { InventoryController } from './inventory/inventory.controller';
import { InventoryService } from './inventory/inventory.service';
import { ReportsController } from './reports/reports.controller';
import { ReportsService } from './reports/reports.service';
import { PdfService } from './reports/pdf/pdf.service';
import { User, UserRole } from './users/user.entity';

/**
 * Guard wiring for the reports and inventory controllers.
 *
 * Regression guard for: these controllers declared @UseGuards(RolesGuard) only.
 * Nothing populated req.user, so RolesGuard's `user?.role === role` check was
 * always false and every @Roles-decorated route returned 403 — including to a
 * legitimately authenticated owner.
 *
 * Uses the real JwtAuthGuard and real RolesGuard with a real JwtService; only
 * the User repository and the domain services are stubbed. No database.
 */
describe('reports + inventory route protection', () => {
  let membershipRole: any = 'OWNER';
  let app: INestApplication;
  let jwt: JwtService;

  const SECRET = 'test-secret';
  const BUSINESS_ID = '11111111-1111-4111-8111-111111111111';

  // businessId values the controllers actually handed to the services.
  const seenBusinessIds: string[] = [];

  // The user row JwtAuthGuard re-reads on every request.
  const dbUser = { id: 'user-1', sessionVersion: 3 };

  const tokenFor = (role: UserRole, overrides: Record<string, any> = {}) => {
    // The membership is the source of truth for role, so the fixture must
    // mirror the token — which is what login does when it signs one.
    membershipRole = role;
    return jwt.sign({
      sub: dbUser.id,
      phone: '+919876543210',
      businessId: BUSINESS_ID,
      role,
      sessionVersion: dbUser.sessionVersion,
      ...overrides,
    });
  };

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: SECRET })],
      controllers: [InventoryController, ReportsController],
      providers: [
        {
          // JwtAuthGuard and AuthService both resolve the caller's role from
          // their membership now, so every spec constructing them needs one.
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            findOneBy: jest.fn(async () => ({ id: 'm-1', role: membershipRole })),
            find: jest.fn(async () => [
              { id: 'm-1', businessId: 'biz-1', role: membershipRole },
            ]),
            create: (o: any) => o,
            save: jest.fn(async (m: any) => m),
          },
        },
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(({ id }) =>
              Promise.resolve(id === dbUser.id ? dbUser : null),
            ),
          },
        },
        {
          provide: InventoryService,
          useValue: {
            findAll: jest.fn((businessId) => {
              seenBusinessIds.push(businessId);
              return Promise.resolve([]);
            }),
            getLowStockAlerts: jest.fn((businessId) => {
              seenBusinessIds.push(businessId);
              return Promise.resolve([]);
            }),
          },
        },
        {
          provide: ReportsService,
          useValue: {
            getCashflow: jest.fn((businessId) => {
              seenBusinessIds.push(businessId);
              return Promise.resolve({ totalIn: 0, totalOut: 0, net: 0 });
            }),
          },
        },
        { provide: PdfService, useValue: {} },
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(() => {
    seenBusinessIds.length = 0;
  });

  describe('without a valid token', () => {
    it.each([
      ['GET /inventory', '/inventory'],
      ['GET /inventory/low-stock', '/inventory/low-stock'],
      ['GET /reports/cashflow', '/reports/cashflow'],
    ])('%s returns 401 when no token is sent', async (_label, url) => {
      await request(app.getHttpServer()).get(url).expect(401);
    });

    it('returns 401 for a token signed with the wrong secret', async () => {
      const forged = new JwtService({ secret: 'not-the-secret' }).sign({
        sub: dbUser.id,
        businessId: BUSINESS_ID,
        role: UserRole.OWNER,
        sessionVersion: dbUser.sessionVersion,
      });

      await request(app.getHttpServer())
        .get('/inventory')
        .set('Authorization', `Bearer ${forged}`)
        .expect(401);
    });

    it('returns 401 for a stale sessionVersion (logged in elsewhere)', async () => {
      await request(app.getHttpServer())
        .get('/inventory')
        .set('Authorization', `Bearer ${tokenFor(UserRole.OWNER, { sessionVersion: 1 })}`)
        .expect(401);
    });
  });

  describe('with a valid token', () => {
    it('OWNER reaches an OWNER/MANAGER route (regression: used to be 403)', async () => {
      await request(app.getHttpServer())
        .get('/reports/cashflow')
        .set('Authorization', `Bearer ${tokenFor(UserRole.OWNER)}`)
        .expect(200);
    });

    it('MANAGER reaches inventory low-stock', async () => {
      await request(app.getHttpServer())
        .get('/inventory/low-stock')
        .set('Authorization', `Bearer ${tokenFor(UserRole.MANAGER)}`)
        .expect(200);
    });

    it('CASHIER reaches a route that permits CASHIER', async () => {
      await request(app.getHttpServer())
        .get('/inventory')
        .set('Authorization', `Bearer ${tokenFor(UserRole.CASHIER)}`)
        .expect(200);
    });

    it('CASHIER is still refused an OWNER/MANAGER route', async () => {
      await request(app.getHttpServer())
        .get('/inventory/low-stock')
        .set('Authorization', `Bearer ${tokenFor(UserRole.CASHIER)}`)
        .expect(403);
    });

    it('CASHIER is still refused reports', async () => {
      await request(app.getHttpServer())
        .get('/reports/cashflow')
        .set('Authorization', `Bearer ${tokenFor(UserRole.CASHIER)}`)
        .expect(403);
    });
  });

  describe('tenancy', () => {
    it("uses the token's businessId, not the old 'biz_default' fallback", async () => {
      await request(app.getHttpServer())
        .get('/inventory')
        .set('Authorization', `Bearer ${tokenFor(UserRole.OWNER)}`)
        .expect(200);

      await request(app.getHttpServer())
        .get('/reports/cashflow')
        .set('Authorization', `Bearer ${tokenFor(UserRole.OWNER)}`)
        .expect(200);

      expect(seenBusinessIds).toEqual([BUSINESS_ID, BUSINESS_ID]);
      expect(seenBusinessIds).not.toContain('biz_default');
    });
  });
});
