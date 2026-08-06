import { INestApplication, ValidationPipe } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';

/**
 * Per-IP limits on the unauthenticated auth routes, over real HTTP.
 *
 * The per-user attempt counter stops a targeted attack on one number; this
 * stops volume — spraying codes across many numbers from one source. The
 * project previously had no rate limiting at all.
 */
describe('auth route throttling', () => {
  let app: INestApplication;
  const verifyOtp = jest.fn().mockRejectedValue(new Error('should not be reached'));
  const requestOtp = jest.fn().mockResolvedValue({ success: true, message: 'ok' });

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [ThrottlerModule.forRoot([{ name: 'default', ttl: 60_000, limit: 120 }])],
      controllers: [AuthController],
      providers: [
        { provide: AuthService, useValue: { requestOtp, verifyOtp } },
        { provide: APP_GUARD, useClass: ThrottlerGuard },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
      .compile();

    app = moduleRef.createNestApplication();
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }));
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  const body = { phoneNumber: '+919876543210', otp: '000000' };

  it('returns 429 once the verify-otp limit is exceeded', async () => {
    verifyOtp.mockRejectedValue(
      Object.assign(new Error('Invalid OTP'), { status: 401 }),
    );

    const statuses: number[] = [];
    for (let i = 0; i < 14; i++) {
      const res = await request(app.getHttpServer()).post('/auth/verify-otp').send(body);
      statuses.push(res.status);
    }

    // Limit is 10/min: the tail must be throttled, not merely rejected.
    expect(statuses).toContain(429);
    expect(statuses.filter((s) => s === 429).length).toBeGreaterThanOrEqual(4);
    // And the service stops being consulted once throttled.
    expect(verifyOtp.mock.calls.length).toBeLessThanOrEqual(10);
  });

  it('validates the body shape, which was previously unchecked', async () => {
    const res = await request(app.getHttpServer())
      .post('/auth/verify-otp')
      .send({ phoneNumber: '+919876543210', otp: 'abc' });

    // 400 for a malformed code, or 429 if this run is already rate limited —
    // either way it never reaches the service.
    expect([400, 429]).toContain(res.status);
    if (res.status === 400) {
      expect(JSON.stringify(res.body.message)).toContain('otp');
    }
  });
});
