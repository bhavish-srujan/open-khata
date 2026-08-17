import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

const DEFAULT_TIMEOUT_MS = 5000;

/**
 * The single client for the Android SMS Gateway.
 *
 * There were previously two incompatible payload shapes aimed at the same
 * gateway: this service posted `{ phoneNumber, message }` to the bare
 * SMS_GATEWAY_URL, while AuthService and RemindersService posted
 * `{ phoneNumbers: [...], message }` to `<base>/messages`. The latter is what
 * the OTP login path uses in production, so it is the canonical shape and lives
 * here. AuthService and RemindersService now delegate rather than
 * reimplementing it, so the two shapes cannot drift apart again.
 */
@Injectable()
export class SmsService {
  private readonly logger = new Logger(SmsService.name);

  constructor(private configService: ConfigService) {}

  /** True when TextBee is configured; otherwise sends are mocked. */
  isConfigured(): boolean {
    return (
      !!this.configService.get<string>('TEXTBEE_API_KEY')?.trim() &&
      !!this.configService.get<string>('TEXTBEE_DEVICE_ID')?.trim()
    );
  }

  /**
   * Best-effort E.164 for Indian numbers, tolerating the formats the app and
   * the contacts picker produce: 10 digits, 0-prefixed, 91-prefixed, 091, or an
   * already-normalised +91 value.
   */
  normalizeForGateway(value: string): string {
    const raw = (value || '').trim();
    if (!raw) return raw;

    const digits = raw.replace(/\D/g, '');
    if (!digits) return raw;

    if (digits.length === 10) return `+91${digits}`;
    if (digits.length === 11 && digits.startsWith('0'))
      return `+91${digits.slice(1)}`;
    if (digits.length === 12 && digits.startsWith('91')) return `+${digits}`;
    if (digits.length === 13 && digits.startsWith('091'))
      return `+${digits.slice(1)}`;

    return raw.startsWith('+') ? raw : `+${digits}`;
  }

  private get timeoutMs(): number {
    const raw = Number(
      this.configService.get<string>('SMS_GATEWAY_TIMEOUT_MS'),
    );
    return Number.isFinite(raw) && raw > 0 ? raw : DEFAULT_TIMEOUT_MS;
  }

  /**
   * Sends one message via TextBee API. Returns false rather than throwing.
   */
  async sendSms(phoneNumber: string, message: string): Promise<boolean> {
    if (!this.isConfigured()) {
      this.logger.warn(
        `TextBee API not configured. Mocking SMS to ${phoneNumber}: ${message}`,
      );
      return true;
    }

    const apiKey = this.configService.get<string>('TEXTBEE_API_KEY')?.trim();
    const deviceId = this.configService
      .get<string>('TEXTBEE_DEVICE_ID')
      ?.trim();
    const targetUrl = `https://api.textbee.dev/api/v1/gateway/devices/${deviceId}/send-sms`;

    const payload = {
      receivers: [this.normalizeForGateway(phoneNumber)],
      smsBody: message,
    };

    try {
      this.logger.debug(`Sending SMS to ${phoneNumber} via TextBee`);
      const response = await axios.post(targetUrl, payload, {
        headers: {
          'Content-Type': 'application/json',
          'x-api-key': apiKey,
        },
        timeout: this.timeoutMs,
      });

      this.logger.log(
        `Successfully sent SMS to ${phoneNumber}. TextBee responded with status: ${response.status}`,
      );
      return true;
    } catch (error) {
      const detail =
        error?.response?.data ||
        error?.code ||
        error?.message ||
        'unknown error';
      this.logger.error(
        `Failed to send SMS via TextBee to ${phoneNumber}: ${JSON.stringify(detail)}`,
      );
      return false;
    }
  }
}
