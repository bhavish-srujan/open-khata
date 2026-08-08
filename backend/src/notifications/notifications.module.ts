import { Global, Module } from '@nestjs/common';
import { SmsService } from './sms/sms.service';
import { WhatsAppService } from './whatsapp/whatsapp.service';

@Global()
@Module({
  providers: [SmsService, WhatsAppService],
  exports: [SmsService, WhatsAppService]
})
export class NotificationsModule {}
