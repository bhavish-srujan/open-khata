import { Controller, Post, Body, HttpCode, HttpStatus, UseGuards } from '@nestjs/common';
import { RemindersService } from './reminders.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('api/v1/reminders')
export class RemindersController {
  constructor(private readonly remindersService: RemindersService) {}

  @Post('send')
  @HttpCode(HttpStatus.OK)
  sendReminder(@Body() body: { phoneNumber: string, amount: number, partyName: string }) {
    return this.remindersService.sendReminder(body.phoneNumber, body.amount, body.partyName);
  }
}
