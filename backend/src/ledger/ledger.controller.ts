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
} from '@nestjs/common';
import { LedgerService } from './ledger.service';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('api/v1/ledger')
export class LedgerController {
  constructor(private readonly ledgerService: LedgerService) {}

  private extractBusinessId(headers: Record<string, string>): string {
    const businessId = headers['x-business-id'];
    if (!businessId) {
      throw new BadRequestException('x-business-id header is required');
    }
    return businessId as string;
  }

  @Get('party/:partyId')
  getLedgerByParty(@Headers() headers, @Param('partyId') partyId: string) {
    const businessId = this.extractBusinessId(headers);
    return this.ledgerService.findByParty(partyId, businessId);
  }

  @Post()
  createLedgerEntry(@Headers() headers, @Body() dto: CreateLedgerEntryDto) {
    const businessId = this.extractBusinessId(headers);
    return this.ledgerService.create(businessId, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteLedgerEntry(@Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(headers);
    return this.ledgerService.delete(id, businessId);
  }
}
