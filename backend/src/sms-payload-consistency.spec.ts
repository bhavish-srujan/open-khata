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
 * Previously SmsService (welcome + transaction messages) posted
 * `{ phoneNumber, message }` to the bare SMS_GATEWAY_URL while AuthService and
 * RemindersService posted `{ phoneNumbers: [...], message }` to
 * `<base>/messages`. Only the latter matched the gateway, so the two
 * SmsService-backed messages had been failing silently.
 *
 * This drives all four senders through their real code paths and asserts they
 * produce identical request envelopes.
 */
describe('SMS payload consistency across every sender', () => {
  let membershipRole: any = 'OWNER';
  const GATEWAY = 'http://gateway.local:8080';
  const EXPECTED_URL = 'http://gateway.local:8080/messages';
  const PHONE = '9876543210';
  const E164 = '+919876543210';

  const env: Record<string, string> = {
    SMS_GATEWAY_URL: GATEWAY,
    SMS_GATEWAY_USERNAME: 'user',
    SMS_GATEWAY_PASSWORD: 'pass',
  };

  let smsService: SmsService;
  let authService: AuthService;
  let remindersService: RemindersService;
  let partyService: PartyService;

  beforeEach(async () => {
    jest.clearAllMocks();
    mockedAxios.post.mockResolvedValue({ status: 200 } as any);

    const moduleRef = await Test.createTestingModule({
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
        SmsService,
        AuthService,
        RemindersService,
        PartyService,
        { provide: ConfigService, useValue: { get: (k: string) => env[k] } },
        {
          provide: DataSource,
          // PartyService.delete removes dependents transactionally.
          useValue: { transaction: async (cb) => cb({
            find: async () => [], delete: async () => undefined, remove: async () => undefined,
          }) },
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
          useValue: { findOne: async () => ({ id: 'biz-1', name: 'Test Shop' }) },
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
      phoneNumbers: payload.phoneNumbers,
      payloadKeys: Object.keys(payload).sort(),
      contentType: config.headers['Content-Type'],
      auth: config.auth,
    };
  };

  const EXPECTED_ENVELOPE = {
    url: EXPECTED_URL,
    phoneNumbers: [E164],
    payloadKeys: ['message', 'phoneNumbers'],
    contentType: 'application/json',
    auth: { username: 'user', password: 'pass' },
  };

  it('OTP send (AuthService) uses the canonical envelope', async () => {
    await authService.requestOtp(PHONE);

    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(capturedEnvelope()).toEqual(EXPECTED_ENVELOPE);
  });

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

  it('all four senders produce byte-identical envelopes', async () => {
    const envelopes: any[] = [];

    for (const send of [
      () => authService.requestOtp(PHONE),
      () => remindersService.sendReminder(PHONE, 500, 'Rahul Traders'),
      () =>
        partyService.create('biz-1', {
          name: 'Rahul Traders',
          phone: PHONE,
          type: 'CUSTOMER' as const,
        }),
      () => smsService.sendSms(PHONE, 'transaction recorded'),
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

    it('OTP surfaces the failure to the client', async () => {
      await expect(authService.requestOtp(PHONE)).rejects.toThrow(
        'Failed to send OTP',
      );
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
