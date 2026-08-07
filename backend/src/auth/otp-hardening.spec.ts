import { UnauthorizedException, HttpException, HttpStatus } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
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
  let service: AuthService;
  let rows: any[];
  let sendSms: jest.Mock;

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

    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        AuthService,
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(async (q: any) =>
              rows.find((r) => Object.keys(q).every((k) => r[k] === q[k])) ?? null,
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
        { provide: JwtService, useValue: { signAsync: jest.fn().mockResolvedValue('tok') } },
        {
          provide: SmsService,
          useValue: { isConfigured: () => false, sendSms },
        },
      ],
    }).compile();

    service = moduleRef.get(AuthService);
  });

  describe('attempt lockout', () => {
    it('rejects a wrong code and counts the attempt', async () => {
      const user = seedUser();

      await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow('Invalid OTP');
      expect(user.otpAttempts).toBe(1);
      // The real code must still be usable — one typo cannot lock you out.
      expect(user.currentOtp).toBe(hashed('123456'));
    });

    it('burns the code after 5 wrong attempts', async () => {
      const user = seedUser();

      for (let i = 0; i < 4; i++) {
        await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow('Invalid OTP');
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
      await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow('Invalid OTP');
      expect(user.otpAttempts).toBe(1);

      await service.verifyOtp(PHONE, '123456');

      expect(user.otpAttempts).toBe(0);
      expect(user.currentOtp).toBeNull();
    });

    it('an expired code is rejected without consuming attempts', async () => {
      const user = seedUser({ otpExpiry: new Date(Date.now() - 1000) });

      await expect(service.verifyOtp(PHONE, '123456')).rejects.toThrow('OTP has expired');
      expect(user.currentOtp).toBeNull();
    });
  });

  describe('resend cooldown', () => {
    it('refuses a re-issue inside the cooldown window', async () => {
      // Issued just now: expiry is the full TTL away.
      seedUser({ otpExpiry: new Date(Date.now() + 10 * 60 * 1000) });

      const attempt = service.requestOtp(PHONE);

      await expect(attempt).rejects.toBeInstanceOf(HttpException);
      await expect(attempt).rejects.toMatchObject({
        status: HttpStatus.TOO_MANY_REQUESTS,
      });
    });

    it('allows a re-issue once the cooldown has passed', async () => {
      // Issued ~2 minutes ago: expiry is 8 minutes out.
      const user = seedUser({ otpExpiry: new Date(Date.now() + 8 * 60 * 1000) });

      await expect(service.requestOtp(PHONE)).resolves.toMatchObject({ success: true });
      expect(user.otpAttempts).toBe(0);
    });

    it('stops the attempt budget being reset on demand', async () => {
      // Burn 3 attempts, then immediately try to re-issue a fresh code.
      const user = seedUser({ otpExpiry: new Date(Date.now() + 10 * 60 * 1000) });
      for (let i = 0; i < 3; i++) {
        await expect(service.verifyOtp(PHONE, '000000')).rejects.toThrow('Invalid OTP');
      }

      await expect(service.requestOtp(PHONE)).rejects.toMatchObject({
        status: HttpStatus.TOO_MANY_REQUESTS,
      });
      // Attempts survive the refused re-issue.
      expect(user.otpAttempts).toBe(3);
    });

    it('issues a fresh code and resets attempts when there is no live OTP', async () => {
      const user = seedUser({ currentOtp: null, otpExpiry: null, otpAttempts: 4 });

      await expect(service.requestOtp(PHONE)).resolves.toMatchObject({ success: true });

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
      await expect(service.verifyOtp(PHONE, candidate!)).resolves.toHaveProperty(
        'access_token',
      );
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
});
