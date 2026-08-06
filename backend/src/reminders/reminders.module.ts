import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { RemindersController } from './reminders.controller';
import { RemindersService } from './reminders.service';
import { CronSchedulerService } from './cron-scheduler.service';
import { Party } from '../party/party.entity';

// SmsService comes from the global NotificationsModule.
@Module({
  imports: [TypeOrmModule.forFeature([Party])],
  controllers: [RemindersController],
  providers: [RemindersService, CronSchedulerService]
})
export class RemindersModule {}
