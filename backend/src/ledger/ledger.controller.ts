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

  private extractBusinessId(req: any, headers: Record<string, string>): string {
    const businessId = req.user?.businessId || headers['x-business-id'];
    if (!businessId) {
      throw new BadRequestException('businessId is required in token or x-business-id header');
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

