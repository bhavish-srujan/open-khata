import {
  Controller,
  Get,
  Post,
  Delete,
  Body,
  Param,
  HttpCode,
  HttpStatus,
  UseGuards,
  Headers,
  BadRequestException,
  Req,
} from '@nestjs/common';
import { LedgerService } from './ledger.service';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('api/v1/ledger')
export class LedgerController {
  constructor(private readonly ledgerService: LedgerService) {}

  /**
   * The business comes from the verified JWT only. It used to fall back to an
   * x-business-id header, which would have let a caller name any business they
   * liked had a token ever lacked the claim.
   */
  private extractBusinessId(req: any, _headers: Record<string, string>): string {
    const businessId = req.user?.businessId;
    if (!businessId) {
      throw new BadRequestException('Token does not carry a businessId');
    }
    return businessId as string;
  }

  @Get('party/:partyId')
  getLedgerByParty(@Req() req, @Headers() headers, @Param('partyId') partyId: string) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.findByParty(partyId, businessId);
  }

  @Post()
  createLedgerEntry(@Req() req, @Headers() headers, @Body() dto: CreateLedgerEntryDto) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.create(businessId, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteLedgerEntry(@Req() req, @Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.delete(id, businessId);
  }
}

