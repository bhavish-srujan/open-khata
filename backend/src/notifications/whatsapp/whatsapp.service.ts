import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

const DEFAULT_TIMEOUT_MS = 8000;
const GRAPH_VERSION = 'v21.0';

/** Why a send failed, so callers can react without parsing Graph errors. */
export type WhatsAppFailure =
  | 'not_configured'
  | 'recipient_not_allowed'
  | 'invalid_number'
  | 'template_rejected'
  | 'rate_limited'
  | 'auth_failed'
  | 'unknown';

export interface WhatsAppResult {
  sent: boolean;
  failure?: WhatsAppFailure;
}

/**
 * Sends the login code over WhatsApp via the Meta Graph API.
 *
 * Only the OTP goes this way. Balance updates and collection reminders stay on
 * SMS: they reach customers who have never installed WhatsApp and who never
 * opted into a business-initiated conversation.
 *
 * Authentication templates take the code as a body parameter and repeat it in a
 * one-tap copy button. Meta requires the template to be pre-approved; there is
 * no free-text fallback for business-initiated messages.
 */
@Injectable()
export class WhatsAppService {
  private readonly logger = new Logger(WhatsAppService.name);

  constructor(private readonly config: ConfigService) {}

  isConfigured(): boolean {
    return Boolean(
      this.config.get<string>('WHATSAPP_ACCESS_TOKEN')?.trim() &&
        this.config.get<string>('WHATSAPP_PHONE_NUMBER_ID')?.trim() &&
        this.config.get<string>('WHATSAPP_OTP_TEMPLATE_NAME')?.trim(),
    );
  }

  /**
   * Best-effort E.164 without the plus, which is what Graph expects.
   * Mirrors the SMS gateway's normalisation so one stored number works for both.
   */
  private normalize(value: string): string {
    const digits = (value || '').replace(/\D/g, '');
    if (!digits) return '';
    if (digits.length === 10) return `91${digits}`;
    if (digits.length === 11 && digits.startsWith('0')) return `91${digits.slice(1)}`;
    if (digits.length === 13 && digits.startsWith('091')) return digits.slice(1);
    return digits;
  }

  private classify(error: any): WhatsAppFailure {
    const status = error?.response?.status;
    const code = error?.response?.data?.error?.code;
    const subcode = error?.response?.data?.error?.error_subcode;

    if (status === 401 || status === 403 || code === 190) return 'auth_failed';
    if (status === 429 || code === 4 || code === 80007) return 'rate_limited';
    // 132xxx is the template family: missing, unapproved, or wrong parameters.
    if (typeof code === 'number' && code >= 132000 && code < 133000) {
      return 'template_rejected';
    }
    // Test numbers may only message recipients registered in the dashboard.
    // Seen in practice as the first failure on a fresh sandbox setup.
    if (code === 131030) return 'recipient_not_allowed';
    if (code === 131031) return 'auth_failed';

    if (code === 131026 || code === 131047 || subcode === 2494010) {
      // Undeliverable: not a WhatsApp user, or outside the allowed window.
      return 'invalid_number';
    }
    return 'unknown';
  }

  /**
   * Sends the code as an authentication template.
   *
   * The code is never logged, at any level. It is a live credential for the ten
   * minutes it is valid, and logs outlive that by a long way.
   */
  async sendOtp(phoneNumber: string, code: string): Promise<WhatsAppResult> {
    if (!this.isConfigured()) {
      this.logger.warn(
        `WhatsApp not configured. Would have sent a login code to ${phoneNumber}.`,
      );
      return { sent: false, failure: 'not_configured' };
    }

    const to = this.normalize(phoneNumber);
    if (!to) {
      return { sent: false, failure: 'invalid_number' };
    }

    const phoneNumberId = this.config.get<string>('WHATSAPP_PHONE_NUMBER_ID')!.trim();
    const template = this.config.get<string>('WHATSAPP_OTP_TEMPLATE_NAME')!.trim();
    const language = this.config.get<string>('WHATSAPP_OTP_TEMPLATE_LANG')?.trim() || 'en';
    const timeoutMs =
      Number(this.config.get<string>('WHATSAPP_TIMEOUT_MS')) || DEFAULT_TIMEOUT_MS;

    const payload = {
      messaging_product: 'whatsapp',
      to,
      type: 'template',
      template: {
        name: template,
        language: { code: language },
        components: [
          { type: 'body', parameters: [{ type: 'text', text: code }] },
          {
            // Authentication templates require the code repeated on the
            // one-tap copy button; Graph rejects the message without it.
            type: 'button',
            sub_type: 'url',
            index: '0',
            parameters: [{ type: 'text', text: code }],
          },
        ],
      },
    };

    try {
      await axios.post(
        `https://graph.facebook.com/${GRAPH_VERSION}/${phoneNumberId}/messages`,
        payload,
        {
          timeout: timeoutMs,
          headers: {
            Authorization: `Bearer ${this.config.get<string>('WHATSAPP_ACCESS_TOKEN')!.trim()}`,
            'Content-Type': 'application/json',
          },
        },
      );

      this.logger.log(`Login code delivered over WhatsApp to ${phoneNumber}`);
      return { sent: true };
    } catch (error) {
      const failure = this.classify(error);
      // Graph echoes the request in some error bodies, so only the classified
      // reason and the provider's message are logged — never the payload.
      const detail = error?.response?.data?.error?.message || error?.code || 'unknown';
      this.logger.error(
        `WhatsApp OTP send failed for ${phoneNumber} [${failure}]: ${detail}`,
      );
      return { sent: false, failure };
    }
  }
}
