import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';
import { SmsService } from './sms.service';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

/**
 * Canonical SMS gateway payload.
 *
 * Two shapes previously targeted the same gateway: this service posted
 * `{ phoneNumber, message }` to the bare SMS_GATEWAY_URL, while the OTP and
 * reminder paths posted `{ phoneNumbers: [...], message }` to `<base>/messages`.
 * The latter is what the working login flow uses, so it is canonical and the
 * assertions below pin it.
 */
describe('SmsService', () => {
  let service: SmsService;
  let env: Record<string, string>;

  const build = async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      providers: [
        SmsService,
        { provide: ConfigService, useValue: { get: (k: string) => env[k] } },
      ],
    }).compile();
    return moduleRef.get(SmsService);
  };

  beforeEach(async () => {
    jest.clearAllMocks();
    env = {
      SMS_GATEWAY_URL: 'http://gateway.local:8080',
      SMS_GATEWAY_USERNAME: 'user',
      SMS_GATEWAY_PASSWORD: 'pass',
    };
    mockedAxios.post.mockResolvedValue({ status: 200 } as any);
    service = await build();
  });

  describe('payload shape', () => {
    it('posts phoneNumbers[] and message — never the singular phoneNumber', async () => {
      await service.sendSms('9876543210', 'hello');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload).toEqual({
        phoneNumbers: ['+919876543210'],
        message: 'hello',
      });
      expect(payload).not.toHaveProperty('phoneNumber');
    });

    it('sends JSON with basic auth and a timeout', async () => {
      await service.sendSms('9876543210', 'hello');

      const [, , config] = mockedAxios.post.mock.calls[0] as any;
      expect(config.headers['Content-Type']).toBe('application/json');
      expect(config.auth).toEqual({ username: 'user', password: 'pass' });
      expect(config.timeout).toBe(5000);
    });

    it('honours SMS_GATEWAY_TIMEOUT_MS', async () => {
      env.SMS_GATEWAY_TIMEOUT_MS = '1200';
      service = await build();

      await service.sendSms('9876543210', 'hello');

      const [, , config] = mockedAxios.post.mock.calls[0] as any;
      expect(config.timeout).toBe(1200);
    });
  });

  describe('target url', () => {
    it.each([
      ['http://gateway.local:8080', 'http://gateway.local:8080/messages'],
      ['http://gateway.local:8080/', 'http://gateway.local:8080/messages'],
      ['http://gateway.local:8080/messages', 'http://gateway.local:8080/messages'],
      ['http://gateway.local:8080/api', 'http://gateway.local:8080/api/messages'],
      ['http://gateway.local:8080/api/', 'http://gateway.local:8080/api/messages'],
    ])('%s -> %s', async (configured, expected) => {
      env.SMS_GATEWAY_URL = configured;
      service = await build();

      await service.sendSms('9876543210', 'hello');

      expect(mockedAxios.post.mock.calls[0][0]).toBe(expected);
    });
  });

  describe('phone normalisation', () => {
    it.each([
      ['9876543210', '+919876543210'],
      ['09876543210', '+919876543210'],
      ['919876543210', '+919876543210'],
      ['0919876543210', '+919876543210'],
      ['+91 98765 43210', '+919876543210'],
      ['+91-98765-43210', '+919876543210'],
      ['(098) 765 43210', '+919876543210'],
    ])('%s -> %s', async (input, expected) => {
      await service.sendSms(input, 'hello');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload.phoneNumbers).toEqual([expected]);
    });

    it('leaves an already-E.164 foreign number alone', async () => {
      await service.sendSms('+14155550123', 'hello');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload.phoneNumbers).toEqual(['+14155550123']);
    });
  });

  describe('failure handling', () => {
    it('mocks the send when no gateway is configured', async () => {
      env.SMS_GATEWAY_URL = '';
      service = await build();

      await expect(service.sendSms('9876543210', 'hello')).resolves.toBe(true);
      expect(mockedAxios.post).not.toHaveBeenCalled();
    });

    it('returns false instead of throwing when the gateway errors', async () => {
      mockedAxios.post.mockRejectedValue(new Error('ECONNREFUSED'));

      await expect(service.sendSms('9876543210', 'hello')).resolves.toBe(false);
    });
  });
});
