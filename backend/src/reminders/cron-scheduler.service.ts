import { Injectable, Logger } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Party } from '../party/party.entity';
import { RemindersService } from './reminders.service';

@Injectable()
export class CronSchedulerService {
  private readonly logger = new Logger(CronSchedulerService.name);

  constructor(
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    private readonly remindersService: RemindersService,
  ) {}

  @Cron(CronExpression.EVERY_DAY_AT_9AM)
  async handleDailyReminders() {
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
    // There is still no due-date model — every customer in debit is reminded on
    // each run, not only those genuinely overdue.
    const partiesWhoOweUs = await this.partyRepository
      .createQueryBuilder('party')
      .where('party.type = :type', { type: 'CUSTOMER' })
      .andWhere('party.currentBalance > 0')
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
