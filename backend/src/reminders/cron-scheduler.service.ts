import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Cron, CronExpression } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Party } from '../party/party.entity';
import { RemindersService } from './reminders.service';

/** Balances at or below this are treated as rounding dust, not debts worth an SMS. */
const DEFAULT_MIN_BALANCE = 1;

@Injectable()
export class CronSchedulerService {
  private readonly logger = new Logger(CronSchedulerService.name);

  constructor(
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    private readonly remindersService: RemindersService,
    private readonly configService: ConfigService,
  ) {}

  /**
   * Off unless explicitly enabled. This job sends real SMS to real customers
   * every morning with no per-party throttle, so it must be opted into rather
   * than inherited by any environment that happens to have an SMS gateway set.
   */
  private get remindersEnabled(): boolean {
    const raw = this.configService.get<string>('REMINDERS_ENABLED');
    return String(raw ?? '').trim().toLowerCase() === 'true';
  }

  /** Smallest balance worth chasing. Must stay above zero or settled parties match. */
  private get minBalance(): number {
    const raw = Number(this.configService.get<string>('REMINDER_MIN_BALANCE'));
    return Number.isFinite(raw) && raw > 0 ? raw : DEFAULT_MIN_BALANCE;
  }

  @Cron(CronExpression.EVERY_DAY_AT_9AM)
  async handleDailyReminders() {
    if (!this.remindersEnabled) {
      this.logger.log(
        'Automated reminders are disabled. Set REMINDERS_ENABLED=true to turn them on.',
      );
      return;
    }

    this.logger.debug('Running daily reminder check...');

    // Sign convention, as implemented by LedgerService.create:
    //   CUSTOMER  GAVE -> +amount, GOT -> -amount, so POSITIVE means they owe us
    //   SUPPLIER  GAVE -> -amount, GOT -> +amount, so POSITIVE means we owe them
    // The app surfaces the same reading: PartiesScreen labels a customer with a
    // positive balance "You will get".
    //
    // A collection reminder must therefore go to CUSTOMERs in credit to us.
    // Suppliers are deliberately excluded: the sign is inverted for them, so
    // including them would chase people we actually owe money to.
    //
    // There is still no due-date model and no per-party throttle — every
    // customer above the threshold is reminded on each run, not only those
    // genuinely overdue. Narrowing that needs a lastRemindedAt column.
    const minBalance = this.minBalance;
    const partiesWhoOweUs = await this.partyRepository
      .createQueryBuilder('party')
      .where('party.type = :type', { type: 'CUSTOMER' })
      .andWhere('party.currentBalance >= :minBalance', { minBalance })
      .getMany();

    for (const party of partiesWhoOweUs) {
      const amountOwed = Number(party.currentBalance);
      this.logger.debug(`Sending automated reminder to ${party.name} (${party.phone}) for owing ${amountOwed}`);
      if (party.phone) {
        try {
          await this.remindersService.sendReminder(
            party.phone,
            amountOwed,
            party.name,
          );
        } catch (error) {
          this.logger.error(`Failed to send reminder to ${party.name}`, error);
        }
      }
    }
  }
}
