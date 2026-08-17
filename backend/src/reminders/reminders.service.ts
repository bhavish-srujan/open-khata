import {
  Injectable,
  Logger,
  BadRequestException,
  HttpException,
  HttpStatus,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { SmsService } from '../notifications/sms/sms.service';
import { Party } from '../party/party.entity';

/**
 * Minimum gap between manually-triggered reminders for the same party. Guards
 * against a double-tap or a scripted retry spamming one customer; it is not
 * about SMS cost alone; a customer who thinks a duplicate means "the shop is
 * hassling me" burns trust that took one message to build.
 *
 * Scoped to sendReminderForParty only. The daily cron job calls sendReminder()
 * directly, once per qualifying party per run, and is unaffected.
 */
const REMINDER_COOLDOWN_MS = 5 * 60 * 1000;

@Injectable()
export class RemindersService {
  private readonly logger = new Logger(RemindersService.name);

  /**
   * In-memory: acceptable for a same-process cooldown on a single API
   * instance. It resets on restart and does not span horizontally scaled
   * instances — a durable per-party throttle would need a persisted
   * lastRemindedAt column instead. See the audit report's "remaining risks".
   */
  private readonly lastManualSendAt = new Map<string, number>();

  constructor(
    private readonly smsService: SmsService,
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
  ) {}

  /**
   * Entry point for the authenticated API route (RemindersController).
   *
   * The recipient phone number, the amount owed and the name printed in the
   * message are all read from the party row itself, scoped to the caller's
   * own business — never from anything the client supplied. A partyId that
   * does not exist, or that exists in a *different* business, is rejected
   * identically (404), so this cannot be used as a cross-tenant existence
   * oracle any more than PartyService.findOne already is.
   */
  async sendReminderForParty(
    partyId: string,
    businessId: string,
  ): Promise<{ success: boolean; message: string }> {
    const party = await this.partyRepository.findOneBy({
      id: partyId,
      businessId,
    });
    if (!party) {
      throw new NotFoundException(
        `Party ${partyId} not found in business ${businessId}`,
      );
    }
    if (!party.phone) {
      throw new BadRequestException('This party has no phone number on file');
    }

    const cooldownKey = `${businessId}:${partyId}`;
    const lastSent = this.lastManualSendAt.get(cooldownKey);
    if (
      lastSent !== undefined &&
      Date.now() - lastSent < REMINDER_COOLDOWN_MS
    ) {
      const waitSeconds = Math.ceil(
        (REMINDER_COOLDOWN_MS - (Date.now() - lastSent)) / 1000,
      );
      throw new HttpException(
        `A reminder was already sent to this party recently. Please wait ${waitSeconds}s before retrying.`,
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    // Reserved before the network call, not after: two concurrent requests
    // for the same party must not both slip through while the first send is
    // still in flight.
    this.lastManualSendAt.set(cooldownKey, Date.now());

    try {
      return await this.sendReminder(
        party.phone,
        Number(party.currentBalance),
        party.name,
      );
    } catch (error) {
      // A failed send must not burn the cooldown — the caller may legitimately retry.
      this.lastManualSendAt.delete(cooldownKey);
      throw error;
    }
  }

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
