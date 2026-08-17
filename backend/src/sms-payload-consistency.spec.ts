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
 * Every outbound SMS must hit the gateway with one payload shape.
 *
 * This suite previously pinned an earlier, generic-gateway design
 * (SMS_GATEWAY_URL, Basic Auth, `{phoneNumbers, message}`). That design was
 * superseded by the TextBee integration SmsService actually implements
 * today (TEXTBEE_API_KEY / TEXTBEE_DEVICE_ID, x-api-key auth, `{receivers,
 * smsBody}`) — see sms.service.spec.ts for the direct coverage of that
 * contract.
 *
 * OTP delivery does not go over WhatsApp: there is no WhatsApp integration in
 * this codebase at all. AuthService.requestOtp() calls SmsService.sendSms()
 * directly and exclusively — TextBee is the only OTP provider, with no
 * fallback. So OTP is not an exception here: all four senders (reminder,
 * welcome message, per-transaction update, and OTP) must agree on one
 * envelope, and that is what this file asserts.
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

  it('reminder send (RemindersService) uses the canonical envelope', async () => {
    await remindersService.sendReminder(PHONE, 500, 'Rahul Traders');

    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(capturedEnvelope()).toEqual(EXPECTED_ENVELOPE);
  });

  it('welcome send (PartyService) uses the canonical envelope', async () => {
    await partyService.create('biz-1', {
      name: 'Rahul Traders',
      phone: PHONE,
      type: 'CUSTOMER',
    });

    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(capturedEnvelope()).toEqual(EXPECTED_ENVELOPE);
  });

  it('direct send (SmsService) uses the canonical envelope', async () => {
    await smsService.sendSms(PHONE, 'transaction recorded');

    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(capturedEnvelope()).toEqual(EXPECTED_ENVELOPE);
  });

  it('OTP send also uses the canonical envelope — it is not a special case', async () => {
    await authService.requestOtp(PHONE);

    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(capturedEnvelope()).toEqual(EXPECTED_ENVELOPE);
  });

  it('every SMS sender, OTP included, produces a byte-identical envelope', async () => {
    const envelopes: any[] = [];

    for (const send of [
      () => remindersService.sendReminder(PHONE, 500, 'Rahul Traders'),
      () =>
        partyService.create('biz-1', {
          name: 'Rahul Traders',
          phone: PHONE,
          type: 'CUSTOMER' as const,
        }),
      () => smsService.sendSms(PHONE, 'transaction recorded'),
      () => authService.requestOtp(PHONE),
    ]) {
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
      await expect(
        remindersService.sendReminder(PHONE, 500, 'Rahul Traders'),
      ).rejects.toThrow('Failed to send Reminder');
    });

    it('party create still succeeds when the welcome SMS fails', async () => {
      await expect(
        partyService.create('biz-1', {
          name: 'Rahul Traders',
          phone: PHONE,
          type: 'CUSTOMER',
        }),
      ).resolves.toMatchObject({ name: 'Rahul Traders' });
    });
  });
});
