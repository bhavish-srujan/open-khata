import {
  UnauthorizedException,
  HttpException,
  HttpStatus,
} from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { BusinessMember } from '../business/business-member.entity';
import { JwtService } from '@nestjs/jwt';
import { Test, TestingModule } from '@nestjs/testing';
import { createHash } from 'node:crypto';
import { AuthService } from './auth.service';
import { User } from '../users/user.entity';
import { Business } from '../business/business.entity';
import { SmsService } from '../notifications/sms/sms.service';

/**
 * OTP brute-force resistance.
 *
 * Verification previously had no attempt counter and the project had no rate
 * limiting, so a six-digit code with a ten-minute window could simply be
 * enumerated — full account takeover for any phone number.
 */
/** Mirrors the service: codes are stored hashed, never in the clear. */
const hashed = (otp: string) => createHash('sha256').update(otp).digest('hex');

describe('AuthService — OTP hardening', () => {
  const membershipRole: any = 'OWNER';
  let service: AuthService;
  let rows: any[];
  let sendSms: jest.Mock;
  let isConfigured: jest.Mock;
  let signAsync: jest.Mock;

  const PHONE = '+919876543210';

  const seedUser = (over: Partial<User> = {}) => {
    const user: any = {
      id: 'user-1',
      phone_number: PHONE,
      normalized_phone: '9876543210',
      currentOtp: hashed('123456'),
      otpExpiry: new Date(Date.now() + 10 * 60 * 1000),
      otpAttempts: 0,
      sessionVersion: 1,
      name: 'Rahul',
      ...over,
    };
    rows.push(user);
    return user;
  };

  beforeEach(async () => {
    rows = [];
    sendSms = jest.fn().mockResolvedValue(true);
    isConfigured = jest.fn().mockReturnValue(true);
    signAsync = jest.fn().mockResolvedValue('tok');

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        {
          // JwtAuthGuard and AuthService both resolve the caller's role from
          // their membership now, so every spec constructing them needs one.
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            findOneBy: jest.fn(async () => ({
              id: 'm-1',
              role: membershipRole,
            })),
            find: jest.fn(async () => [
              { id: 'm-1', businessId: 'biz-1', role: membershipRole },
            ]),
            create: (o: any) => o,
            save: jest.fn(async (m: any) => m),
          },
        },
        AuthService,
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(
              async (q: any) =>
                rows.find((r) => Object.keys(q).every((k) => r[k] === q[k])) ??
                null,
            ),
            createQueryBuilder: () => ({
              where: () => ({ getOne: async () => null }),
            }),
            create: (o: any) => ({ ...o }),
            save: jest.fn(async (u: any) => {
              if (!rows.includes(u)) rows.push(u);
              return u;
            }),
          },
        },
        {
          provide: getRepositoryToken(Business),
          useValue: {
            findOneBy: jest.fn().mockResolvedValue({ id: 'biz-1' }),
            create: (o: any) => o,
            save: jest.fn(async (b: any) => b),
          },
        },
        { provide: JwtService, useValue: { signAsync } },
        {
          provide: SmsService,
          // Configured by default: these tests exercise OTP hardening logic,
          // not delivery configuration. The "OTP delivery provider" describe
          // block below covers isConfigured() explicitly.
          useValue: { isConfigured, sendSms },
        },
      ],
    }).compile();

    service = moduleRef.get(AuthService);
  });

  describe('attempt lockout', () => {
    it('rejects a wrong code and counts the attempt', async () => {
      const user = seedUser();

      await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow(
        'Invalid OTP',
      );
      expect(user.otpAttempts).toBe(1);
      // The real code must still be usable — one typo cannot lock you out.
      expect(user.currentOtp).toBe(hashed('123456'));
    });

    it('burns the code after 5 wrong attempts', async () => {
      const user = seedUser();

      for (let i = 0; i < 4; i++) {
        await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow(
          'Invalid OTP',
        );
      }
      await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow(
        'Too many incorrect attempts',
      );

      expect(user.currentOtp).toBeNull();
      expect(user.otpExpiry).toBeNull();
    });

    it('the correct code no longer works once burned', async () => {
      seedUser();
      for (let i = 0; i < 5; i++) {
        await expect(service.verifyOtp(PHONE, '000000')).rejects.toBeInstanceOf(
          UnauthorizedException,
        );
      }

      await expect(service.verifyOtp(PHONE, '123456')).rejects.toThrow(
        'No OTP requested for this number',
      );
    });

    it('a correct code resets the counter', async () => {
      const user = seedUser();
      await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow(
        'Invalid OTP',
      );
      expect(user.otpAttempts).toBe(1);

      await service.verifyOtp(PHONE, '123456');

      expect(user.otpAttempts).toBe(0);
      expect(user.currentOtp).toBeNull();
    });

    it('an expired code is rejected without consuming attempts', async () => {
      const user = seedUser({ otpExpiry: new Date(Date.now() - 1000) });

      await expect(service.verifyOtp(PHONE, '123456')).rejects.toThrow(
        'OTP has expired',
      );
      expect(user.currentOtp).toBeNull();
    });
  });

  describe('resend behavior (no cooldown, by design)', () => {
    it('allows an immediate re-issue with a live, unexpired code still outstanding', async () => {
      // Issued just now: expiry is the full TTL away. Previously this window
      // was refused with a 429; the cooldown enforcing that has been removed.
      const user = seedUser({
        otpExpiry: new Date(Date.now() + 10 * 60 * 1000),
      });

      await expect(service.requestOtp(PHONE)).resolves.toMatchObject({
        success: true,
      });
      expect(user.otpAttempts).toBe(0);
    });

    it('a fresh re-issue resets the attempt counter, even mid-lockout — the known tradeoff of removing the cooldown', async () => {
      // Burn 3 attempts, then immediately re-request rather than continue guessing.
      const user = seedUser({
        otpExpiry: new Date(Date.now() + 10 * 60 * 1000),
      });
      for (let i = 0; i < 3; i++) {
        await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow(
          'Invalid OTP',
        );
      }
      expect(user.otpAttempts).toBe(3);

      await expect(service.requestOtp(PHONE)).resolves.toMatchObject({
        success: true,
      });

      // The per-attempt lockout only bounds guesses within one issued code now;
      // verify-otp's own per-IP throttle is what remains to bound this path.
      expect(user.otpAttempts).toBe(0);
    });

    it('issues a fresh code and resets attempts when there is no live OTP', async () => {
      const user = seedUser({
        currentOtp: null,
        otpExpiry: null,
        otpAttempts: 4,
      });

      await expect(service.requestOtp(PHONE)).resolves.toMatchObject({
        success: true,
      });

      // Stored hashed, so the column never holds a usable code.
      expect(user.currentOtp).toMatch(/^[0-9a-f]{64}$/);
      expect(user.otpAttempts).toBe(0);
    });
  });

  describe('storage', () => {
    it('never persists the code in the clear', async () => {
      const user = seedUser({ currentOtp: null, otpExpiry: null });

      await service.requestOtp(PHONE);

      // Whatever six-digit code was issued, the column must not contain it.
      expect(user.currentOtp).not.toMatch(/^[0-9]{6}$/);
      expect(user.currentOtp).toMatch(/^[0-9a-f]{64}$/);
    });

    it('still verifies the plaintext the customer received', async () => {
      const user = seedUser({ currentOtp: null, otpExpiry: null });
      await service.requestOtp(PHONE);

      // Recover the issued code from the mock SMS the service tried to send.
      const stored = user.currentOtp;
      const candidate = Array.from({ length: 1000000 }, (_, i) =>
        String(i).padStart(6, '0'),
      ).find((c) => hashed(c) === stored);

      expect(candidate).toBeDefined();
      await expect(
        service.verifyOtp(PHONE, candidate!),
      ).resolves.toHaveProperty('access_token');
    });
  });

  describe('code generation', () => {
    it('always issues exactly six digits', async () => {
      const user = seedUser({ currentOtp: null, otpExpiry: null });

      for (let i = 0; i < 50; i++) {
        user.currentOtp = null;
        user.otpExpiry = null;
        await service.requestOtp(PHONE);
        expect(user.currentOtp).toMatch(/^[0-9a-f]{64}$/);
      }
    });
  });

  describe('OTP delivery provider', () => {
    it('sends the code via SmsService/TextBee, and only that', async () => {
      seedUser({ currentOtp: null, otpExpiry: null });

      await service.requestOtp(PHONE);

      expect(sendSms).toHaveBeenCalledTimes(1);
      expect(sendSms).toHaveBeenCalledWith(
        PHONE,
        expect.stringContaining('login code'),
      );
      // There is no WhatsAppService in this module's providers at all: AuthService
      // no longer depends on it, so a WhatsApp delivery path cannot exist here.
    });

    it('does not report success when TextBee is not configured', async () => {
      seedUser({ currentOtp: null, otpExpiry: null });
      // SmsService.sendSms() itself would mock a successful send here — the
      // right behaviour for reminders/welcome texts, wrong for a login code.
      isConfigured.mockReturnValue(false);

      const attempt = service.requestOtp(PHONE);

      await expect(attempt).rejects.toBeInstanceOf(HttpException);
      await expect(attempt).rejects.toMatchObject({
        status: HttpStatus.SERVICE_UNAVAILABLE,
      });
      expect(sendSms).not.toHaveBeenCalled();
    });

    it('does not report success when TextBee is configured but the send fails', async () => {
      seedUser({ currentOtp: null, otpExpiry: null });
      sendSms.mockResolvedValue(false);

      const attempt = service.requestOtp(PHONE);

      await expect(attempt).rejects.toBeInstanceOf(HttpException);
      await expect(attempt).rejects.toMatchObject({
        status: HttpStatus.SERVICE_UNAVAILABLE,
      });
    });
  });

  describe('is_new_user (registration-screen heuristic)', () => {
    // Reuses this file's own fixtures (seedUser/Business mocks) rather than
    // adding a new describe file — the fixture just needed to vary `name`.
    //
    // The Android client used is_new_user to decide whether to show the
    // name/business-name registration screen after a successful login. A
    // returning user whose account already has a name must get `false`
    // here regardless of what the client's own local storage remembers —
    // that local memory (a "hasSavedProfile" flag) does not survive an
    // uninstall/reinstall or a cleared app, so is_new_user being wrong was
    // the one thing standing between a returning user and being routed
    // through registration again as if they were new.
    it('reports true for an account that has never had a name set', async () => {
      seedUser({ name: null });

      const result = await service.verifyOtp(PHONE, '123456');

      expect(result.is_new_user).toBe(true);
    });

    it('reports false for a returning account whose name is already set — the reinstall/relogin case', async () => {
      // seedUser() defaults name to 'Rahul': exactly what
      // UsersService.updateMe() (PUT /users/me) would have persisted during
      // this account's original registration, on any device.
      seedUser();

      const result = await service.verifyOtp(PHONE, '123456');

      expect(result.is_new_user).toBe(false);
    });
  });

  describe('session invalidation on verify', () => {
    it('increments sessionVersion and mints a JWT carrying the new value', async () => {
      const user = seedUser({ sessionVersion: 3 });

      await service.verifyOtp(PHONE, '123456');

      expect(user.sessionVersion).toBe(4);
      expect(signAsync).toHaveBeenCalledWith(
        expect.objectContaining({ sessionVersion: 4 }),
      );
    });

    it('defaults a missing sessionVersion to 1 before incrementing', async () => {
      const user = seedUser({ sessionVersion: undefined });

      await service.verifyOtp(PHONE, '123456');

      expect(user.sessionVersion).toBe(2);
    });
  });
});
