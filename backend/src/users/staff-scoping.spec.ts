import { INestApplication, ValidationPipe } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { UsersController } from './users.controller';
import { UsersService } from './users.service';
import { User, UserRole } from './user.entity';
import { BusinessMember } from '../business/business-member.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';

/**
 * Staff are scoped to one business.
 *
 * Role was a single global column on User and nothing associated a user with a
 * business, so getStaff() returned every MANAGER and CASHIER in the database —
 * names and phone numbers belonging to other shops — to any owner who asked,
 * and addStaff() created users attached to nothing.
 *
 * Uses the real JwtAuthGuard and RolesGuard so authorisation is genuinely
 * exercised; only the repositories are stubbed.
 */
describe('staff scoping', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const M_OWNER = '44444444-4444-4444-8444-444444444401';
  const M_A = '44444444-4444-4444-8444-444444444402';
  const M_B = '44444444-4444-4444-8444-444444444403';

  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';

  const owner = { id: 'user-owner', sessionVersion: 1, phone_number: '+919000000001', name: 'Owner' };
  const cashierA = { id: 'user-cashier-a', sessionVersion: 1, phone_number: '+919000000002', name: 'Cashier A', normalized_phone: '9000000002' };
  const cashierB = { id: 'user-cashier-b', sessionVersion: 1, phone_number: '+919000000003', name: 'Cashier B', normalized_phone: '9000000003' };

  let users: any[];
  let members: any[];

  const member = (id: string, businessId: string, userId: string, role: UserRole, user: any) =>
    Object.assign(new BusinessMember(), {
      id, businessId, userId, role, user, createdAt: new Date(),
    });

  beforeEach(async () => {
    users = [owner, cashierA, cashierB];
    members = [
      member(M_OWNER, BUSINESS_A, owner.id, UserRole.OWNER, owner),
      member(M_A, BUSINESS_A, cashierA.id, UserRole.CASHIER, cashierA),
      // Belongs to a different business entirely.
      member(M_B, BUSINESS_B, cashierB.id, UserRole.CASHIER, cashierB),
    ];

    const matches = (row: any, where: any) =>
      Object.entries(where).every(([k, v]) => row[k] === v);

    const moduleRef = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [UsersController],
      providers: [
        UsersService,
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(async (w: any) => users.find((u) => matches(u, w)) ?? null),
            create: (o: any) => ({ ...o }),
            save: jest.fn(async (u: any) => {
              u.id = u.id ?? `user-${users.length}`;
              if (!users.includes(u)) users.push(u);
              return u;
            }),
          },
        },
        {
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            // Matches on whichever keys the query carries, like a real
            // repository: hardcoding them would let an unscoped query pass.
            findOneBy: jest.fn(async (w: any) => members.find((m) => matches(m, w)) ?? null),
            find: jest.fn(async ({ where }: any) => members.filter((m) => matches(m, where))),
            create: (o: any) => Object.assign(new BusinessMember(), { ...o, createdAt: new Date() }),
            save: jest.fn(async (m: any) => {
              m.id = m.id ?? `m-${members.length}`;
              if (!members.includes(m)) members.push(m);
              return m;
            }),
            remove: jest.fn(async (m: any) => {
              members = members.filter((x) => x.id !== m.id);
              return m;
            }),
          },
        },
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }));
    await app.init();
  });

  afterEach(async () => {
    await app.close();
  });

  const token = (user: any, businessId: string, role = UserRole.OWNER) =>
    jwt.sign({
      sub: user.id, phone: user.phone_number, businessId, role,
      sessionVersion: user.sessionVersion,
    });

  const auth = (req: any, t: string) => req.set('Authorization', `Bearer ${t}`);

  describe('listing', () => {
    it('returns only this business\'s members', async () => {
      const res = await auth(
        request(app.getHttpServer()).get('/users/staff'),
        token(owner, BUSINESS_A),
      ).expect(200);

      const ids = res.body.map((s: any) => s.userId);
      expect(ids).toContain(cashierA.id);
      // The regression: another business's cashier must not appear.
      expect(ids).not.toContain(cashierB.id);
    });

    it('never exposes OTP or session state', async () => {
      const res = await auth(
        request(app.getHttpServer()).get('/users/staff'),
        token(owner, BUSINESS_A),
      ).expect(200);

      const serialised = JSON.stringify(res.body);
      expect(serialised).not.toContain('currentOtp');
      expect(serialised).not.toContain('sessionVersion');
      expect(serialised).not.toContain('otpAttempts');
    });
  });

  describe('adding', () => {
    const body = { phoneNumber: '+919876500099', name: 'New Cashier', role: 'CASHIER' };

    it('adds a member to the caller\'s business', async () => {
      const res = await auth(
        request(app.getHttpServer()).post('/users/staff'),
        token(owner, BUSINESS_A),
      ).send(body).expect(201);

      expect(res.body.role).toBe('CASHIER');
      expect(members.some((m) => m.businessId === BUSINESS_A && m.role === 'CASHIER' && m.id === res.body.membershipId)).toBe(true);
    });

    it('reuses an existing account rather than duplicating a phone number', async () => {
      const before = users.length;

      await auth(
        request(app.getHttpServer()).post('/users/staff'),
        token(owner, BUSINESS_A),
      ).send({ ...body, phoneNumber: cashierB.phone_number }).expect(201);

      // cashierB already exists; a second account would break their login.
      expect(users.length).toBe(before);
      expect(members.some((m) => m.businessId === BUSINESS_A && m.userId === cashierB.id)).toBe(true);
    });

    it('refuses a duplicate membership', async () => {
      await auth(
        request(app.getHttpServer()).post('/users/staff'),
        token(owner, BUSINESS_A),
      ).send({ ...body, phoneNumber: cashierA.phone_number }).expect(400);
    });

    it('refuses to grant OWNER', async () => {
      const res = await auth(
        request(app.getHttpServer()).post('/users/staff'),
        token(owner, BUSINESS_A),
      ).send({ ...body, role: 'OWNER' });

      expect(res.status).toBe(400);
    });

    it('ignores a businessId in the body', async () => {
      const res = await auth(
        request(app.getHttpServer()).post('/users/staff'),
        token(owner, BUSINESS_A),
      ).send({ ...body, businessId: BUSINESS_B });

      // forbidNonWhitelisted rejects it outright rather than silently ignoring.
      expect(res.status).toBe(400);
    });
  });

  describe('removing', () => {
    it('removes the membership but keeps the user account', async () => {
      const before = users.length;

      await auth(
        request(app.getHttpServer()).delete(`/users/staff/${M_A}`),
        token(owner, BUSINESS_A),
      ).expect(204);

      expect(members.some((m) => m.id === M_A)).toBe(false);
      // The person may work at another shop; their account must survive.
      expect(users.length).toBe(before);
    });

    it('cannot remove a member of another business', async () => {
      const res = await auth(
        request(app.getHttpServer()).delete(`/users/staff/${M_B}`),
        token(owner, BUSINESS_A),
      );

      expect(res.status).toBe(404);
      expect(members.some((m) => m.id === M_B)).toBe(true);
    });

    it('cannot remove an owner', async () => {
      await auth(
        request(app.getHttpServer()).delete(`/users/staff/${M_OWNER}`),
        token(owner, BUSINESS_A),
      ).expect(400);
    });

    it('cannot remove yourself', async () => {
      const res = await auth(
        request(app.getHttpServer()).delete(`/users/staff/${M_A}`),
        token(cashierA, BUSINESS_A, UserRole.MANAGER),
      );

      expect(res.status).toBe(403);
    });
  });

  describe('role resolution comes from the membership, not the token', () => {
    it('refuses a token whose businessId the user is not a member of', async () => {
      // A stolen or stale token naming another business must not work.
      await auth(
        request(app.getHttpServer()).get('/users/staff'),
        token(owner, BUSINESS_B),
      ).expect(401);
    });

    it('revoking a membership takes effect immediately, without re-login', async () => {
      const cashierToken = token(cashierA, BUSINESS_A, UserRole.MANAGER);

      // The token claims MANAGER, but the membership says CASHIER, so the
      // owner/manager-only listing must be refused.
      await auth(request(app.getHttpServer()).get('/users/staff'), cashierToken)
        .expect(403);

      // Now remove them entirely: the same token stops working altogether.
      members = members.filter((m) => m.id !== M_A);
      await auth(request(app.getHttpServer()).get('/users/staff'), cashierToken)
        .expect(401);
    });

    it('a self-claimed OWNER role in the token is ignored', async () => {
      // Forging role: 'OWNER' must not grant owner powers.
      const forged = token(cashierA, BUSINESS_A, UserRole.OWNER);

      await auth(request(app.getHttpServer()).get('/users/staff'), forged).expect(403);
    });
  });
});
