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
      const payload = {
        textMessage: {
          text: message
        },
        phoneNumbers: [phoneNumber]
      };
      
      const authHeader = 'Basic ' + Buffer.from(`${this.smsGatewayUser}:${this.smsGatewayPass}`).toString('base64');

      await firstValueFrom(
        this.httpService.post(this.smsGatewayUrl, payload, { 
          headers: { 
            Authorization: authHeader,
            'Content-Type': 'application/json'
          } 
        })
      );
      
      return { success: true, message: 'Reminder Sent successfully' };
    } catch (error) {
      this.logger.error('Error sending Reminder via Android SMS Gateway', error);
      throw new BadRequestException('Failed to send Reminder');
    }
  }
}
