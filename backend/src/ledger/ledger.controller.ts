import {
  Controller,
  Get,
  Post,
  Patch,
  Delete,
  Body,
  Param,
  Query,
  HttpCode,
  HttpStatus,
  UseGuards,
  Headers,
  BadRequestException,
  Req,
} from '@nestjs/common';
import { LedgerService } from './ledger.service';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';
import { ListLedgerQueryDto } from './dto/list-ledger-query.dto';
import { UpdateLedgerEntryDto } from './dto/update-ledger-entry.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from '../users/user.entity';

// JwtAuthGuard must run first: it populates req.user, which RolesGuard reads.
@UseGuards(JwtAuthGuard, RolesGuard)
@Controller('api/v1/ledger')
export class LedgerController {
  constructor(private readonly ledgerService: LedgerService) {}

  /**
   * The business comes from the verified JWT only. It used to fall back to an
   * x-business-id header, which would have let a caller name any business they
   * liked had a token ever lacked the claim.
   */
  private extractBusinessId(
    req: any,
    _headers: Record<string, string>,
  ): string {
    const businessId = req.user?.businessId;
    if (!businessId) {
      throw new BadRequestException('Token does not carry a businessId');
    }
    return businessId as string;
  }

  /** Business-wide feed backing the cashbook. Declared before the more specific
   * 'party/:partyId' route for readability; Nest matches on the literal path so
   * ordering is not load-bearing here. */
  @Get()
  getLedgerForBusiness(
    @Req() req,
    @Headers() headers,
    @Query() query: ListLedgerQueryDto,
  ) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.findByBusiness(businessId, query);
  }

  @Get('party/:partyId')
  getLedgerByParty(
    @Req() req,
    @Headers() headers,
    @Param('partyId') partyId: string,
  ) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.findByParty(partyId, businessId);
  }

  @Post()
  createLedgerEntry(
    @Req() req,
    @Headers() headers,
    @Body() dto: CreateLedgerEntryDto,
  ) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.create(businessId, dto);
  }

  /** Restricted to OWNER/MANAGER: editing an amount moves money between a
   *  party's recorded balance and reality, and CASHIER should not be able to
   *  alter an entry — including one they didn't record — after the fact. */
  @Patch(':id')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  updateLedgerEntry(
    @Req() req,
    @Headers() headers,
    @Param('id') id: string,
    @Body() dto: UpdateLedgerEntryDto,
  ) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.update(id, businessId, dto);
  }

  /** Restricted to OWNER/MANAGER, matching update: deleting reverses the
   *  balance delta an entry applied, so the same risk applies. */
  @Delete(':id')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteLedgerEntry(@Req() req, @Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(req, headers);
    return this.ledgerService.delete(id, businessId);
  }
}
