import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

const DEFAULT_TIMEOUT_MS = 5000;

/**
 * SMS delivery, split by purpose.
 *
 * TextBee is an OTP-delivery channel only. It is a personal-device gateway
 * with a small monthly allowance, and routing customer-facing traffic through
 * it both exhausts that allowance (which then takes login down for everyone,
 * since OTP shares the same quota) and sends business messages from a number
 * customers have no relationship with.
 *
 * So there are two distinct entry points, and callers must pick deliberately:
 *
 *   sendOtpSms()           -> TextBee. Authentication only.
 *   sendTransactionalSms() -> a separately configured transactional provider.
 *
 * There is deliberately no transactional provider configured today, so
 * sendTransactionalSms() is a no-op that reports "not sent". That is the
 * intended state: the alternative — quietly falling back to TextBee — is the
 * bug this split exists to remove. Wiring a real provider means implementing
 * only `deliverTransactional` below; no caller changes.
 */
@Injectable()
export class SmsService {
  private readonly logger = new Logger(SmsService.name);

  constructor(private configService: ConfigService) {}

  /** True when TextBee is configured. OTP-path only — see class comment. */
  isConfigured(): boolean {
    return (
      !!this.configService.get<string>('TEXTBEE_API_KEY')?.trim() &&
      !!this.configService.get<string>('TEXTBEE_DEVICE_ID')?.trim()
    );
  }

  /**
   * True when a dedicated transactional SMS provider is configured.
   *
   * Deliberately independent of [isConfigured]: TextBee being available must
   * never make transactional sending look available.
   */
  isTransactionalConfigured(): boolean {
    return !!this.configService
      .get<string>('TRANSACTIONAL_SMS_PROVIDER')
      ?.trim();
  }

  /**
   * Customer-facing SMS (ledger entries, party welcome, reminders).
   *
   * Returns false without sending while no transactional provider is
   * configured. Every caller already treats a false return as "not
   * delivered" and continues — the ledger write, party create and reminder
   * run all complete regardless, because none of them has ever depended on
   * SMS succeeding.
   */
  async sendTransactionalSms(
    phoneNumber: string,
    message: string,
  ): Promise<boolean> {
    if (!this.isTransactionalConfigured()) {
      this.logger.log(
        `Transactional SMS not configured; skipping message to ${phoneNumber}. ` +
          `TextBee is reserved for OTP and is intentionally not used here.`,
      );
      return false;
    }
    return this.deliverTransactional(phoneNumber, message);
  }

  /**
   * Hook for a real transactional provider. Unreachable until
   * TRANSACTIONAL_SMS_PROVIDER is set, at which point this is the only method
   * that needs an implementation.
   */
  private async deliverTransactional(
    phoneNumber: string,
    _message: string,
  ): Promise<boolean> {
    this.logger.warn(
      `TRANSACTIONAL_SMS_PROVIDER is set but no transport is implemented; ` +
        `message to ${phoneNumber} was not sent.`,
    );
    return false;
  }

  /** OTP delivery via TextBee. Authentication messages only. */
  async sendOtpSms(phoneNumber: string, message: string): Promise<boolean> {
    return this.sendViaTextBee(phoneNumber, message);
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
   * Sends one message via the TextBee API. Returns false rather than throwing.
   *
   * Private: reaching TextBee goes through [sendOtpSms], so a future caller
   * cannot route customer traffic here by accident.
   */
  private async sendViaTextBee(
    phoneNumber: string,
    message: string,
  ): Promise<boolean> {
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
