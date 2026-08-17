import { IsUUID } from 'class-validator';

/**
 * The only thing the client gets to choose is *which* party. The recipient
 * phone number, the amount owed and the name printed in the message are all
 * resolved server-side from that party's row — see RemindersService.
 * sendReminderForParty — so there is nothing here for `phoneNumber`,
 * `amount` or `partyName` to override even by accident; the global
 * ValidationPipe's forbidNonWhitelisted rejects them outright.
 */
export class SendReminderDto {
  @IsUUID()
  partyId: string;
}
