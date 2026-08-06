import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import { SmsService } from '../notifications/sms/sms.service';

@Injectable()
export class RemindersService {
  private readonly logger = new Logger(RemindersService.name);

  constructor(private readonly smsService: SmsService) {}

  async sendReminder(
    phoneNumber: string,
    amount: number,
    partyName: string,
  ): Promise<{ success: boolean; message: string }> {
    if (!phoneNumber) {
      throw new BadRequestException('Phone number is required');
    }

    const message = `Dear ${partyName}, this is a gentle reminder that an amount of Rs. ${amount} is pending. Please clear it at the earliest.`;

    // URL building, E.164 normalisation and the payload shape all live in
    // SmsService, so this path and the OTP path cannot diverge again.
    const sent = await this.smsService.sendSms(phoneNumber, message);

    if (!sent) {
      this.logger.error(`Failed to send reminder to ${phoneNumber}`);
      throw new BadRequestException('Failed to send Reminder');
    }

    return {
      success: true,
      message: this.smsService.isConfigured()
        ? 'Reminder Sent successfully'
        : 'Reminder Sent successfully (Mock)',
    };
  }
}
