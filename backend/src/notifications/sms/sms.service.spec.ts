import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';
import { SmsService } from './sms.service';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

/**
 * The TextBee gateway contract.
 *
 * This suite previously pinned an earlier, generic-gateway design —
 * SMS_GATEWAY_URL with Basic Auth, posting `{phoneNumbers, message}` to
 * `<base>/messages`. That design was superseded by the TextBee integration
 * (TEXTBEE_API_KEY / TEXTBEE_DEVICE_ID, x-api-key auth, `{receivers,
 * smsBody}` posted to a fixed per-device TextBee URL) that SmsService
 * actually implements today. TextBee is the sole SMS/OTP provider — there is
 * no WhatsApp or other fallback anywhere in this codebase. This rewrite
 * ports the same assertions (payload shape, target URL, phone
 * normalisation, auth, timeout, failure handling) onto that real contract.
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
      TEXTBEE_API_KEY: 'txb_test_key',
      TEXTBEE_DEVICE_ID: 'device-123',
    };
    mockedAxios.post.mockResolvedValue({ status: 200 });
    service = await build();
  });

  describe('payload shape', () => {
    it("posts receivers[] and smsBody — TextBee's field names, not the generic gateway's", async () => {
      await service.sendOtpSms('9876543210', 'hello');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload).toEqual({
        receivers: ['+919876543210'],
        smsBody: 'hello',
      });
      expect(payload).not.toHaveProperty('phoneNumbers');
      expect(payload).not.toHaveProperty('message');
    });

    it('sends JSON with the API key header and a timeout — not Basic Auth', async () => {
      await service.sendOtpSms('9876543210', 'hello');

      const [, , config] = mockedAxios.post.mock.calls[0] as any;
      expect(config.headers['Content-Type']).toBe('application/json');
      expect(config.headers['x-api-key']).toBe('txb_test_key');
      expect(config.auth).toBeUndefined();
      expect(config.timeout).toBe(5000);
    });

    it('honours SMS_GATEWAY_TIMEOUT_MS', async () => {
      env.SMS_GATEWAY_TIMEOUT_MS = '1200';
      service = await build();

      await service.sendOtpSms('9876543210', 'hello');

      const [, , config] = mockedAxios.post.mock.calls[0] as any;
      expect(config.timeout).toBe(1200);
    });
  });

  describe('target url', () => {
    it('is built from TEXTBEE_DEVICE_ID', async () => {
      await service.sendOtpSms('9876543210', 'hello');

      expect(mockedAxios.post.mock.calls[0][0]).toBe(
        'https://api.textbee.dev/api/v1/gateway/devices/device-123/send-sms',
      );
    });

    it.each([
      [
        'device-123',
        'https://api.textbee.dev/api/v1/gateway/devices/device-123/send-sms',
      ],
      [
        'another-device',
        'https://api.textbee.dev/api/v1/gateway/devices/another-device/send-sms',
      ],
    ])('device id %s -> %s', async (deviceId, expected) => {
      env.TEXTBEE_DEVICE_ID = deviceId;
      service = await build();

      await service.sendOtpSms('9876543210', 'hello');

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
      await service.sendOtpSms(input, 'hello');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload.receivers).toEqual([expected]);
    });

    it('leaves an already-E.164 foreign number alone', async () => {
      await service.sendOtpSms('+14155550123', 'hello');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload.receivers).toEqual(['+14155550123']);
    });
  });

  describe('failure handling', () => {
    it('mocks the send when TextBee is not configured', async () => {
      env = {};
      service = await build();

      await expect(service.sendOtpSms('9876543210', 'hello')).resolves.toBe(true);
      expect(mockedAxios.post).not.toHaveBeenCalled();
    });

    it('mocks the send when only one of the two required values is set', async () => {
      env = { TEXTBEE_API_KEY: 'txb_test_key' };
      service = await build();

      await expect(service.sendOtpSms('9876543210', 'hello')).resolves.toBe(true);
      expect(mockedAxios.post).not.toHaveBeenCalled();
    });

    it('returns false instead of throwing when the gateway errors', async () => {
      mockedAxios.post.mockRejectedValue(new Error('ECONNREFUSED'));

      await expect(service.sendOtpSms('9876543210', 'hello')).resolves.toBe(false);
    });
  });

  describe('isConfigured', () => {
    it('is true once both TextBee values are set', () => {
      expect(service.isConfigured()).toBe(true);
    });

    it('is false when unset', async () => {
      env = {};
      service = await build();

      expect(service.isConfigured()).toBe(false);
    });
  });
});
