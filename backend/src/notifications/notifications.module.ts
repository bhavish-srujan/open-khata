import { Global, Module } from '@nestjs/common';
import { SmsService } from './sms/sms.service';

@Global()
@Module({
  providers: [SmsService],
  exports: [SmsService]
})
export class NotificationsModule {}
