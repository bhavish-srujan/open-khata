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
    // We assume any party with a negative balance (they owe money) and 
    // a collection frequency that suggests they are due should get a reminder.
    // In a real app, we would have a specific "dueDate" field in the Party or Bill.
    // For now, we mock finding customers who owe money:
    const overdueParties = await this.partyRepository
      .createQueryBuilder('party')
      .where('party.currentBalance < 0')
      .getMany();

    for (const party of overdueParties) {
      this.logger.debug(`Sending automated reminder to ${party.name} (${party.phone}) for owing ${Math.abs(party.currentBalance)}`);
      if (party.phone) {
        try {
          await this.remindersService.sendReminder(
            party.phone,
            Math.abs(party.currentBalance),
            party.name,
          );
        } catch (error) {
          this.logger.error(`Failed to send reminder to ${party.name}`, error);
        }
      }
    }
  }
}
