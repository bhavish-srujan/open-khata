import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { RemindersController } from './reminders.controller';
import { RemindersService } from './reminders.service';
import { CronSchedulerService } from './cron-scheduler.service';
import { HttpModule } from '@nestjs/axios';
import { Party } from '../party/party.entity';

@Module({
  imports: [HttpModule, TypeOrmModule.forFeature([Party])],
  controllers: [RemindersController],
  providers: [RemindersService, CronSchedulerService]
})
export class RemindersModule {}
