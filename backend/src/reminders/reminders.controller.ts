import {
  Controller,
  Post,
  Body,
  HttpCode,
  HttpStatus,
  UseGuards,
  BadRequestException,
  Req,
} from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { RemindersService } from './reminders.service';
import { SendReminderDto } from './dto/send-reminder.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from '../users/user.entity';

// JwtAuthGuard must run first: it populates req.user, which RolesGuard reads.
@UseGuards(JwtAuthGuard, RolesGuard)
@Controller('api/v1/reminders')
export class RemindersController {
  constructor(private readonly remindersService: RemindersService) {}

  /**
   * The business comes from the verified JWT only, matching every other
   * controller in this API (see PartyController/LedgerController) — never
   * from anything the client puts in the request body.
   */
  private extractBusinessId(req: any): string {
    const businessId = req.user?.businessId;
    if (!businessId) {
      throw new BadRequestException('Token does not carry a businessId');
    }
    return businessId as string;
  }

  /**
   * The recipient, amount and name are resolved server-side from the party
   * row, never trusted from the client — see RemindersService.
   * sendReminderForParty. The client's only say is *which* party, and that
   * is checked against the caller's own (membership-verified) business, so
   * this route cannot be used to message an arbitrary phone number or pull
   * another business's ledger data into an SMS.
   *
   * Restricted to OWNER/MANAGER, matching ReportsController's treatment of
   * other financial/customer-communication actions — a CASHIER can record
   * transactions but not send collection messages on the business's behalf.
   */
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  @Throttle({ default: { ttl: 60_000, limit: 5 } })
  @Post('send')
  @HttpCode(HttpStatus.OK)
  sendReminder(@Req() req, @Body() dto: SendReminderDto) {
    const businessId = this.extractBusinessId(req);
    return this.remindersService.sendReminderForParty(dto.partyId, businessId);
  }
}
