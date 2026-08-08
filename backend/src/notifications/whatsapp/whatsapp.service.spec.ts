import { ConfigService } from '@nestjs/config';
import axios from 'axios';
import { WhatsAppService } from './whatsapp.service';

jest.mock('axios');
const mockedAxios = axios as jest.Mocked<typeof axios>;

/**
 * Login codes over the Meta Graph API.
 *
 * Only the OTP goes this way; balance updates and reminders stay on SMS, since
 * those reach customers who may never have installed WhatsApp.
 */
describe('WhatsAppService', () => {
  let env: Record<string, string>;
  let service: WhatsAppService;

  const build = () =>
    new WhatsAppService({ get: (k: string) => env[k] } as unknown as ConfigService);

  const graphError = (status: number, code?: number, subcode?: number) =>
    Object.assign(new Error('graph'), {
      response: { status, data: { error: { code, error_subcode: subcode, message: 'x' } } },
    });

  beforeEach(() => {
    jest.clearAllMocks();
    env = {
      WHATSAPP_ACCESS_TOKEN: 'token',
      WHATSAPP_PHONE_NUMBER_ID: '123456',
      WHATSAPP_OTP_TEMPLATE_NAME: 'login_code',
    };
    mockedAxios.post.mockResolvedValue({ status: 200, data: {} } as any);
    service = build();
  });

  describe('configuration', () => {
    it('is configured when all three settings are present', () => {
      expect(service.isConfigured()).toBe(true);
    });

    it.each(['WHATSAPP_ACCESS_TOKEN', 'WHATSAPP_PHONE_NUMBER_ID', 'WHATSAPP_OTP_TEMPLATE_NAME'])(
      'is not configured without %s',
      (missing) => {
        delete env[missing];
        expect(build().isConfigured()).toBe(false);
      },
    );

    it('reports not_configured rather than attempting a call', async () => {
      env = {};

      const result = await build().sendOtp('+919876543210', '123456');

      expect(result).toEqual({ sent: false, failure: 'not_configured' });
      expect(mockedAxios.post).not.toHaveBeenCalled();
    });
  });

  describe('successful send', () => {
    it('posts an authentication template to the messages endpoint', async () => {
      const result = await service.sendOtp('+919876543210', '654321');

      expect(result).toEqual({ sent: true });
      const [url, payload, config] = mockedAxios.post.mock.calls[0] as any;
      expect(url).toContain('/123456/messages');
      expect(payload.messaging_product).toBe('whatsapp');
      expect(payload.type).toBe('template');
      expect(payload.template.name).toBe('login_code');
      expect(config.headers.Authorization).toBe('Bearer token');
    });

    it('carries the code in the body and on the copy button', async () => {
      await service.sendOtp('+919876543210', '654321');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      const body = payload.template.components.find((c: any) => c.type === 'body');
      const button = payload.template.components.find((c: any) => c.type === 'button');

      expect(body.parameters[0].text).toBe('654321');
      // Graph rejects an authentication template without the button copy.
      expect(button.parameters[0].text).toBe('654321');
    });

    it.each([
      ['9876543210', '919876543210'],
      ['09876543210', '919876543210'],
      ['+91 98765 43210', '919876543210'],
      ['919876543210', '919876543210'],
    ])('normalises %s to %s', async (input, expected) => {
      await service.sendOtp(input, '111111');

      const [, payload] = mockedAxios.post.mock.calls[0] as any;
      expect(payload.to).toBe(expected);
    });
  });

  describe('failures are classified so the caller can react', () => {
    it.each([
      ['unapproved template', graphError(400, 132001), 'template_rejected'],
      ['bad template params', graphError(400, 132000), 'template_rejected'],
      ['expired token', graphError(401, 190), 'auth_failed'],
      ['forbidden', graphError(403), 'auth_failed'],
      ['rate limited', graphError(429), 'rate_limited'],
      ['throughput cap', graphError(400, 80007), 'rate_limited'],
      ['not a whatsapp user', graphError(400, 131026), 'invalid_number'],
      ['something else', graphError(500), 'unknown'],
    ])('maps %s', async (_label, error, expected) => {
      mockedAxios.post.mockRejectedValue(error);

      const result = await service.sendOtp('+919876543210', '123456');

      expect(result.sent).toBe(false);
      expect(result.failure).toBe(expected);
    });

    it('rejects an unusable number without calling Graph', async () => {
      const result = await service.sendOtp('not-a-number', '123456');

      expect(result).toEqual({ sent: false, failure: 'invalid_number' });
      expect(mockedAxios.post).not.toHaveBeenCalled();
    });

    it('never returns the code in a failure result', async () => {
      mockedAxios.post.mockRejectedValue(graphError(400, 132001));

      const result = await service.sendOtp('+919876543210', '999888');

      expect(JSON.stringify(result)).not.toContain('999888');
    });
  });

  describe('the code is never logged', () => {
    it.each([
      ['on success', undefined],
      ['on failure', graphError(400, 132001)],
    ])('%s', async (_label, error) => {
      if (error) mockedAxios.post.mockRejectedValue(error);

      // Capture everything the service writes at any level.
      const written: string[] = [];
      const logger = (service as any).logger;
      for (const level of ['log', 'warn', 'error', 'debug']) {
        jest.spyOn(logger, level as any).mockImplementation((...args: any[]) => {
          written.push(args.map(String).join(' '));
        });
      }

      await service.sendOtp('+919876543210', '424242');

      // An OTP is a live credential for ten minutes; logs outlive that.
      expect(written.join('\n')).not.toContain('424242');
    });
  });
});
