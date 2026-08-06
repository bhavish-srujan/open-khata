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

  /** True when a gateway is configured; otherwise sends are mocked. */
  isConfigured(): boolean {
    return !!this.configService.get<string>('SMS_GATEWAY_URL')?.trim();
  }

  /**
   * SMS_GATEWAY_URL is a base URL; the gateway exposes /messages. Appends it
   * unless the configured value already ends there.
   */
  buildSmsGatewayUrl(): string {
    const url = (this.configService.get<string>('SMS_GATEWAY_URL') || '').trim();
    if (!url) return url;
    try {
      const parsed = new URL(url);
      if (parsed.pathname === '' || parsed.pathname === '/') {
        parsed.pathname = '/messages';
      } else if (!parsed.pathname.endsWith('/messages')) {
        parsed.pathname = parsed.pathname.replace(/\/$/, '') + '/messages';
      }
      return parsed.toString();
    } catch {
      return url.replace(/\/$/, '') + '/messages';
    }
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
    if (digits.length === 11 && digits.startsWith('0')) return `+91${digits.slice(1)}`;
    if (digits.length === 12 && digits.startsWith('91')) return `+${digits}`;
    if (digits.length === 13 && digits.startsWith('091')) return `+${digits.slice(1)}`;

    return raw.startsWith('+') ? raw : `+${digits}`;
  }

  private get timeoutMs(): number {
    const raw = Number(this.configService.get<string>('SMS_GATEWAY_TIMEOUT_MS'));
    return Number.isFinite(raw) && raw > 0 ? raw : DEFAULT_TIMEOUT_MS;
  }

  /**
   * Sends one message. Returns false rather than throwing, because the ledger
   * and party flows fire this off without awaiting and must not let a gateway
   * outage roll back a transaction. Callers that need to surface the failure
   * (OTP, reminders) check the return value.
   */
  async sendSms(phoneNumber: string, message: string): Promise<boolean> {
    if (!this.isConfigured()) {
      this.logger.warn(
        `SMS gateway URL not configured. Mocking SMS to ${phoneNumber}: ${message}`,
      );
      return true;
    }

    const targetUrl = this.buildSmsGatewayUrl();
    const payload = {
      phoneNumbers: [this.normalizeForGateway(phoneNumber)],
      message,
    };

    try {
      this.logger.debug(`Sending SMS to ${phoneNumber} via ${targetUrl}`);
      const response = await axios.post(targetUrl, payload, {
        headers: { 'Content-Type': 'application/json' },
        timeout: this.timeoutMs,
        auth: {
          username: this.configService.get<string>('SMS_GATEWAY_USERNAME') || '',
          password: this.configService.get<string>('SMS_GATEWAY_PASSWORD') || '',
        },
      });

      this.logger.log(
        `Successfully sent SMS to ${phoneNumber}. Gateway responded with status: ${response.status}`,
      );
      return true;
    } catch (error) {
      const detail =
        error?.response?.data || error?.code || error?.message || 'unknown error';
      this.logger.error(`Failed to send SMS to ${phoneNumber}: ${JSON.stringify(detail)}`);
      return false;
    }
  }
}
