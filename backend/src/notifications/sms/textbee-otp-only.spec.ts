import { ConfigService } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import axios from 'axios';
import { SmsService } from './sms.service';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

/**
 * TextBee is an OTP channel and nothing else.
 *
 * Regression cover for the defect this split fixed: every sender shared one
 * sendSms(), so customer-facing messages consumed the same small TextBee
 * allowance that login depends on. Once it ran out, OTP delivery failed and
 * nobody could sign in.
 */
describe('SmsService — TextBee is reserved for OTP', () => {
  const DEVICE_ID = 'device-123';
  const API_KEY = 'txb_test_key';
  const PHONE = '9876543210';

  const build = async (env: Record<string, string>) => {
    const moduleRef = await Test.createTestingModule({
      providers: [
        SmsService,
        { provide: ConfigService, useValue: { get: (k: string) => env[k] } },
      ],
    }).compile();
    return moduleRef.get(SmsService);
  };

  const textBeeEnv = {
    TEXTBEE_API_KEY: API_KEY,
    TEXTBEE_DEVICE_ID: DEVICE_ID,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockedAxios.post.mockResolvedValue({ status: 200 });
  });

  it('sends OTP through TextBee', async () => {
    const service = await build(textBeeEnv);

    await expect(service.sendOtpSms(PHONE, 'your code is 123456')).resolves.toBe(
      true,
    );
    expect(mockedAxios.post).toHaveBeenCalledTimes(1);
    expect(mockedAxios.post.mock.calls[0][0]).toContain('api.textbee.dev');
  });

  it('does NOT send transactional SMS through TextBee, even when TextBee is fully configured', async () => {
    const service = await build(textBeeEnv);

    await expect(
      service.sendTransactionalSms(PHONE, 'you gave Rs 500'),
    ).resolves.toBe(false);
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('reports transactional capability independently of TextBee', async () => {
    const service = await build(textBeeEnv);

    // TextBee being available must never make transactional look available.
    expect(service.isConfigured()).toBe(true);
    expect(service.isTransactionalConfigured()).toBe(false);
  });

  it('still declines to send transactional SMS via TextBee when a provider name is set but unimplemented', async () => {
    const service = await build({
      ...textBeeEnv,
      TRANSACTIONAL_SMS_PROVIDER: 'some-future-provider',
    });

    await expect(
      service.sendTransactionalSms(PHONE, 'you gave Rs 500'),
    ).resolves.toBe(false);
    // The critical part: it must not silently fall back to the OTP gateway.
    expect(mockedAxios.post).not.toHaveBeenCalled();
  });

  it('exposes no public method that routes arbitrary messages to TextBee', () => {
    // sendSms() was the shared entry point every caller used; it is now
    // private so a future caller cannot reach TextBee by accident.
    expect((SmsService.prototype as any).sendSms).toBeUndefined();
  });
});
