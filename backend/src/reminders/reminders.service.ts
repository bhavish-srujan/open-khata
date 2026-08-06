import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import { HttpService } from '@nestjs/axios';
import { ConfigService } from '@nestjs/config';
import { firstValueFrom } from 'rxjs';

@Injectable()
export class RemindersService {
  private readonly logger = new Logger(RemindersService.name);
  private readonly smsGatewayUrl: string;
  private readonly smsGatewayUser: string;
  private readonly smsGatewayPass: string;

  constructor(
    private httpService: HttpService,
    private configService: ConfigService,
  ) {
    this.smsGatewayUrl = this.configService.get<string>('SMS_GATEWAY_URL') || '';
    this.smsGatewayUser = this.configService.get<string>('SMS_GATEWAY_USERNAME') || '';
    this.smsGatewayPass = this.configService.get<string>('SMS_GATEWAY_PASSWORD') || '';
  }

  private normalizeForGateway(value: string): string {
    const digits = (value || '').replace(/\D/g, '');
    if (!digits) return value;
    if (digits.length === 10) return `+91${digits}`;
    if (digits.length === 12 && digits.startsWith('91')) return `+${digits}`;
    return value.startsWith('+') ? value : `+${digits}`;
  }

  private buildSmsGatewayUrl(): string {
    const url = this.smsGatewayUrl.trim();
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

  async sendReminder(phoneNumber: string, amount: number, partyName: string): Promise<{ success: boolean, message: string }> {
    if (!phoneNumber) {
      throw new BadRequestException('Phone number is required');
    }

    const message = `Dear ${partyName}, this is a gentle reminder that an amount of Rs. ${amount} is pending. Please clear it at the earliest.`;

    if (!this.smsGatewayUrl) {
      this.logger.warn(`MOCK SMS: Sending reminder to ${phoneNumber}: ${message}`);
      return { success: true, message: 'Reminder Sent successfully (Mock)' };
    }

    try {
      const targetUrl = this.buildSmsGatewayUrl();
      const payload = {
        phoneNumbers: [this.normalizeForGateway(phoneNumber)],
        message,
      };
      
      const timeoutMs = Number(this.configService.get<number>('SMS_GATEWAY_TIMEOUT_MS') || 5000);
      this.logger.debug(`Sending reminder to SMS gateway URL: ${targetUrl}`);

      await firstValueFrom(
        this.httpService.post(targetUrl, payload, {
          headers: { 'Content-Type': 'application/json' },
          timeout: timeoutMs,
          auth: {
            username: this.smsGatewayUser || '',
            password: this.smsGatewayPass || '',
          },
        }),
      );
      
      return { success: true, message: 'Reminder Sent successfully' };
    } catch (error) {
      const responseData = error?.response?.data || error?.message || 'unknown error';
      this.logger.error('Error sending Reminder via Android SMS Gateway', responseData);
      throw new BadRequestException('Failed to send Reminder');
    }
  }
}
