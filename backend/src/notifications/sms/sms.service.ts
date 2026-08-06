import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';

@Injectable()
export class SmsService {
  private readonly logger = new Logger(SmsService.name);

  constructor(private configService: ConfigService) {}

  async sendSms(phoneNumber: string, message: string): Promise<boolean> {
    const url = this.configService.get<string>('SMS_GATEWAY_URL');
    const username = this.configService.get<string>('SMS_GATEWAY_USERNAME');
    const password = this.configService.get<string>('SMS_GATEWAY_PASSWORD');

    if (!url) {
      this.logger.warn(`SMS gateway URL not configured. Mocking SMS to ${phoneNumber}: ${message}`);
      return true;
    }

    try {
      this.logger.debug(`Sending SMS to ${phoneNumber} via Gateway...`);
      // Assuming a generic JSON payload to the gateway
      // This might need adjustment based on the specific SMS gateway app used
      const timeoutMs = Number(this.configService.get<number>('SMS_GATEWAY_TIMEOUT_MS') || 5000);
      const response = await axios.post(url, {
        phoneNumber: phoneNumber,
        message: message,
      }, {
        timeout: timeoutMs,
        auth: {
          username: username || '',
          password: password || ''
        }
      });
      
      this.logger.log(`Successfully sent SMS to ${phoneNumber}. Gateway responded with status: ${response.status}`);
      return true;
    } catch (error) {
      const errMsg = error?.code || error?.message || 'unknown';
      this.logger.error(`Failed to send SMS to ${phoneNumber}: ${errMsg}`);
      return false;
    }
  }
}
