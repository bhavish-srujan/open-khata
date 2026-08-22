import { ConfigService } from '@nestjs/config';
import { getRepositoryToken } from '@nestjs/typeorm';
import { BusinessMember } from './business/business-member.entity';
import { DataSource } from 'typeorm';
import { Test } from '@nestjs/testing';
import { JwtService } from '@nestjs/jwt';
import axios from 'axios';
import { SmsService } from './notifications/sms/sms.service';
import { AuthService } from './auth/auth.service';
import { RemindersService } from './reminders/reminders.service';
import { PartyService } from './party/party.service';
import { User } from './users/user.entity';
import { Business } from './business/business.entity';
import { Party } from './party/party.entity';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

/**
 * TextBee carries OTP traffic and nothing else.
 *
 * This suite used to assert the opposite: that all four senders (reminder,
 * welcome, per-transaction, OTP) hit TextBee with one identical envelope.
 * That was the bug. TextBee is a personal-device gateway on a small monthly
 * allowance, so customer-facing messages both burned the allowance that OTP
 * login depends on — taking sign-in down for everyone once it ran out — and
 * reached customers from a number with no relationship to the business.
 *
 * The invariant is now the separation itself: only AuthService.requestOtp()
 * may reach TextBee, and it still does so with the canonical envelope
 * (TEXTBEE_API_KEY / TEXTBEE_DEVICE_ID, x-api-key auth, `{receivers,
 * smsBody}`) — see sms.service.spec.ts for direct coverage of that contract.
 * Every other sender routes through sendTransactionalSms(), which sends
 * nothing at all while no transactional provider is configured.
 */
describe('SMS payload consistency across every sender', () => {
  const membershipRole: any = 'OWNER';
  const DEVICE_ID = 'device-123';
  const API_KEY = 'txb_test_key';
  const EXPECTED_URL = `https://api.textbee.dev/api/v1/gateway/devices/${DEVICE_ID}/send-sms`;
  const PHONE = '9876543210';
  const E164 = '+919876543210';

  const env: Record<string, string> = {
    TEXTBEE_API_KEY: API_KEY,
    TEXTBEE_DEVICE_ID: DEVICE_ID,
  };

  let smsService: SmsService;
  let authService: AuthService;
  let remindersService: RemindersService;
  let partyService: PartyService;

  beforeEach(async () => {
    jest.clearAllMocks();
    mockedAxios.post.mockResolvedValue({ status: 200 });

    const moduleRef = await Test.createTestingModule({
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
        SmsService,
        AuthService,
        RemindersService,
        PartyService,
        { provide: ConfigService, useValue: { get: (k: string) => env[k] } },
        {
          provide: DataSource,
          // PartyService.delete removes dependents transactionally.
          useValue: {
            transaction: async (cb) =>
              cb({
                find: async () => [],
                delete: async () => undefined,
                remove: async () => undefined,
              }),
          },
        },
        { provide: JwtService, useValue: { signAsync: jest.fn() } },
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn().mockResolvedValue(null),
            createQueryBuilder: () => ({
              where: () => ({ getOne: async () => null }),
            }),
            create: (o: any) => o,
            save: jest.fn(async (u: any) => u),
          },
        },
        {
          provide: getRepositoryToken(Business),
          // PartyService now resolves the business name for the welcome SMS.
          useValue: {
            findOne: async () => ({ id: 'biz-1', name: 'Test Shop' }),
          },
        },
        {
          provide: getRepositoryToken(Party),
          useValue: {
            create: (o: any) => Object.assign(new Party(), o),
            save: jest.fn(async (p: any) => ({ ...p, id: 'party-1' })),
            findOneBy: jest.fn().mockResolvedValue(null),
          },
        },
      ],
    }).compile();

    smsService = moduleRef.get(SmsService);
    authService = moduleRef.get(AuthService);
    remindersService = moduleRef.get(RemindersService);
    partyService = moduleRef.get(PartyService);
  });

  /** The request the gateway actually received, minus the message body. */
  const capturedEnvelope = () => {
    const [url, payload, config] = mockedAxios.post.mock.calls[0] as any;
    return {
      url,
      receivers: payload.receivers,
      payloadKeys: Object.keys(payload).sort(),
      contentType: config.headers['Content-Type'],
      apiKeyHeader: config.headers['x-api-key'],
    };
  };

  const EXPECTED_ENVELOPE = {
    url: EXPECTED_URL,
    receivers: [E164],
    payloadKeys: ['receivers', 'smsBody'],
    contentType: 'application/json',
    apiKeyHeader: API_KEY,
  };

  it('reminder send does NOT reach TextBee — it is customer-facing, not OTP', async () => {
    // Reports the capability as unavailable rather than quietly falling back
    // to the OTP gateway.
    await expect(
      remindersService.sendReminder(PHONE, 500, 'Rahul Traders'),
    ).rejects.toThrow(/no SMS provider is configured/i);

    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('welcome send does NOT reach TextBee', async () => {
    await partyService.create('biz-1', {
      name: 'Rahul Traders',
      phone: PHONE,
      type: 'CUSTOMER',
    });

    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('transactional send does NOT reach TextBee while no provider is configured', async () => {
    const delivered = await smsService.sendTransactionalSms(
      PHONE,
      'transaction recorded',
    );

    expect(delivered).toBe(false);
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('OTP send DOES use TextBee, with the canonical envelope', async () => {
    await authService.requestOtp(PHONE);

    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(capturedEnvelope()).toEqual(EXPECTED_ENVELOPE);
  });

  it('OTP is the only sender that touches TextBee', async () => {
    const nonOtpSenders = [
      // Rejects (capability unavailable) rather than falling back to TextBee.
      () =>
        remindersService
          .sendReminder(PHONE, 500, 'Rahul Traders')
          .catch(() => undefined),
      () =>
        partyService.create('biz-1', {
          name: 'Rahul Traders',
          phone: PHONE,
          type: 'CUSTOMER' as const,
        }),
      () => smsService.sendTransactionalSms(PHONE, 'transaction recorded'),
    ];

    for (const send of nonOtpSenders) {
      mockedAxios.post.mockClear();
      await send();
      expect(mockedAxios.post).not.toHaveBeenCalled();
    }

    mockedAxios.post.mockClear();
    await authService.requestOtp(PHONE);
    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
  });

  it('the OTP envelope is well-formed', async () => {
    const envelopes: any[] = [];

    for (const send of [() => authService.requestOtp(PHONE)]) {
      mockedAxios.post.mockClear();
      await send();
      envelopes.push(capturedEnvelope());
    }

    for (const envelope of envelopes) {
      expect(envelope).toEqual(envelopes[0]);
    }
  });

  describe('failure propagation is preserved per caller', () => {
    beforeEach(() => {
      mockedAxios.post.mockRejectedValue(new Error('gateway down'));
    });

    it('reminder surfaces the failure to the client', async () => {
      // Still surfaces rather than silently reporting success. The reason
      // differs now: reminders no longer reach TextBee at all, so they fail
      // at the "no transactional provider" check before any gateway call —
      // which is why this asserts a rejection rather than the old
      // gateway-specific message.
      await expect(
        remindersService.sendReminder(PHONE, 500, 'Rahul Traders'),
      ).rejects.toThrow(/no SMS provider is configured|Failed to send Reminder/i);
    });

    it('party create still succeeds when the welcome SMS fails', async () => {
      await expect(
        partyService.create('biz-1', {
          name: 'Rahul Traders',
          phone: PHONE,
          type: 'CUSTOMER',
        }),
      ).resolves.toMatchObject({ name: 'RAHUL TRADERS' });
    });
  });
});
