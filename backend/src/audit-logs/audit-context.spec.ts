import { Controller, Get, INestApplication, UseGuards } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { User } from '../users/user.entity';
import { auditContextMiddleware, getAuditUser } from './audit-context';

/**
 * End-to-end check that the AsyncLocalStorage scope opened by
 * auditContextMiddleware survives the whole pipeline — middleware, then
 * JwtAuthGuard writing the user, then the route handler reading it back.
 *
 * This is the wiring most likely to break silently: a Nest interceptor would not
 * work here, because the handler executes on subscription, after the
 * interceptor's synchronous scope has exited.
 */
@Controller('probe')
class ProbeController {
  /** Reports what the audit subscriber would see at write time. */
  @Get('anonymous')
  anonymous() {
    return { auditUser: getAuditUser() ?? null };
  }

  @UseGuards(JwtAuthGuard)
  @Get('authenticated')
  authenticated() {
    return { auditUser: getAuditUser() ?? null };
  }
}

describe('audit context propagation over HTTP', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const dbUser = { id: 'user-42', sessionVersion: 2 };

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
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    app.use(auditContextMiddleware); // exactly as main.ts does
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  const token = (over: Record<string, any> = {}) =>
    jwt.sign({
      sub: dbUser.id,
      businessId: 'biz-1',
      role: 'OWNER',
      sessionVersion: dbUser.sessionVersion,
      ...over,
    });

  it('carries the authenticated user from the guard into the handler', async () => {
    const res = await request(app.getHttpServer())
      .get('/probe/authenticated')
      .set('Authorization', `Bearer ${token()}`)
      .expect(200);

    expect(res.body.auditUser).toBe('user-42');
  });

  it('leaves the context empty on an unauthenticated route', async () => {
    const res = await request(app.getHttpServer())
      .get('/probe/anonymous')
      .expect(200);

    expect(res.body.auditUser).toBeNull();
  });

  it('does not leak the user across requests', async () => {
    await request(app.getHttpServer())
      .get('/probe/authenticated')
      .set('Authorization', `Bearer ${token()}`)
      .expect(200);

    // A later anonymous request must not inherit the previous request's user.
    const res = await request(app.getHttpServer())
      .get('/probe/anonymous')
      .expect(200);

    expect(res.body.auditUser).toBeNull();
  });

  it('keeps concurrent authenticated requests separate', async () => {
    const other = { id: 'user-99', sessionVersion: 2 };
    const repo = app.get(getRepositoryToken(User));
    repo.findOneBy.mockImplementation(async ({ id }: any) =>
      id === dbUser.id ? dbUser : id === other.id ? other : null,
    );

    const [a, b] = await Promise.all([
      request(app.getHttpServer())
        .get('/probe/authenticated')
        .set('Authorization', `Bearer ${token()}`),
      request(app.getHttpServer())
        .get('/probe/authenticated')
        .set('Authorization', `Bearer ${token({ sub: other.id })}`),
    ]);

    expect(a.body.auditUser).toBe('user-42');
    expect(b.body.auditUser).toBe('user-99');
  });
});
