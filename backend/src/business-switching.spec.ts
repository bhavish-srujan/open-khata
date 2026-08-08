import { Controller, Get, INestApplication, Req, UseGuards } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { JwtAuthGuard } from './auth/jwt-auth.guard';
import { User, UserRole } from './users/user.entity';
import { BusinessMember } from './business/business-member.entity';

/**
 * Switching the active business per request.
 *
 * The token names one business, but a user can belong to several and the app
 * switches between them without re-authenticating. An earlier version of this
 * header was accepted unverified, which let a caller name any business they
 * liked; it is honoured here only after membership is checked, so it is exactly
 * as trustworthy as the token's own claim and no more.
 */
@Controller('probe')
class ProbeController {
  @UseGuards(JwtAuthGuard)
  @Get('active')
  active(@Req() req) {
    return { businessId: req.user.businessId, role: req.user.role };
  }
}

describe('per-request business switching', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const dbUser = { id: 'user-1', sessionVersion: 1 };
  const BIZ_A = '11111111-1111-4111-8111-111111111111';
  const BIZ_B = '22222222-2222-4222-8222-222222222222';
  const BIZ_FOREIGN = '99999999-9999-4999-8999-999999999999';

  // The user owns A and is a cashier in B. They are not in FOREIGN at all.
  const memberships = [
    { businessId: BIZ_A, userId: dbUser.id, role: UserRole.OWNER },
    { businessId: BIZ_B, userId: dbUser.id, role: UserRole.CASHIER },
  ];

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [ProbeController],
      providers: [
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(async ({ id }) => (id === dbUser.id ? dbUser : null)),
          },
        },
        {
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            findOneBy: jest.fn(async (w: any) =>
              memberships.find(
                (m) => m.businessId === w.businessId && m.userId === w.userId,
              ) ?? null,
            ),
          },
        },
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  // The token always names business A, as it would after logging in.
  const token = () =>
    jwt.sign({
      sub: dbUser.id,
      businessId: BIZ_A,
      role: UserRole.OWNER,
      sessionVersion: dbUser.sessionVersion,
    });

  const get = (headers: Record<string, string> = {}) => {
    const req = request(app.getHttpServer())
      .get('/probe/active')
      .set('Authorization', `Bearer ${token()}`);
    Object.entries(headers).forEach(([k, v]) => req.set(k, v));
    return req;
  };

  it("defaults to the token's business when no header is sent", async () => {
    const res = await get().expect(200);

    expect(res.body.businessId).toBe(BIZ_A);
    expect(res.body.role).toBe(UserRole.OWNER);
  });

  it('switches to another business the user belongs to', async () => {
    const res = await get({ 'x-business-id': BIZ_B }).expect(200);

    expect(res.body.businessId).toBe(BIZ_B);
  });

  it('carries the role held in the switched-to business', async () => {
    // Owner in A, cashier in B: the role must follow the business, not the
    // token, or a cashier could act as an owner by switching.
    const res = await get({ 'x-business-id': BIZ_B }).expect(200);

    expect(res.body.role).toBe(UserRole.CASHIER);
  });

  it('refuses a business the user is not a member of', async () => {
    // The regression this header caused before membership existed.
    await get({ 'x-business-id': BIZ_FOREIGN }).expect(401);
  });

  it('refuses a well-formed but unknown business id', async () => {
    await get({ 'x-business-id': '33333333-3333-4333-8333-333333333333' }).expect(401);
  });

  it('ignores a blank header rather than treating it as a switch', async () => {
    const res = await get({ 'x-business-id': '   ' }).expect(200);

    expect(res.body.businessId).toBe(BIZ_A);
  });

  it('does not let a switch survive into the next request', async () => {
    await get({ 'x-business-id': BIZ_B }).expect(200);

    // No header this time: it must fall back to the token, not remember B.
    const res = await get().expect(200);
    expect(res.body.businessId).toBe(BIZ_A);
  });
});
