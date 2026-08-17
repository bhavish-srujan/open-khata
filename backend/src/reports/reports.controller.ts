import {
  Controller,
  Get,
  Query,
  UseGuards,
  Request,
  Res,
} from '@nestjs/common';
import type { Response } from 'express';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { ReportsService } from './reports.service';
import { PdfService } from './pdf/pdf.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from '../users/user.entity';
import { Business } from '../business/business.entity';

// JwtAuthGuard must run first: it populates req.user, which RolesGuard reads.
@Controller('reports')
@UseGuards(JwtAuthGuard, RolesGuard)
export class ReportsController {
  constructor(
    private readonly reportsService: ReportsService,
    private readonly pdfService: PdfService,
    @InjectRepository(Business)
    private readonly businessRepository: Repository<Business>,
  ) {}

  @Get('cashflow')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  async getCashflow(
    @Request() req,
    @Query('startDate') startDateString: string,
    @Query('endDate') endDateString: string,
  ) {
    const businessId = req.user.businessId;
    const startDate = startDateString
      ? new Date(startDateString)
      : new Date(new Date().setMonth(new Date().getMonth() - 1));
    const endDate = endDateString ? new Date(endDateString) : new Date();

    return this.reportsService.getCashflow(businessId, startDate, endDate);
  }

  @Get('sales')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  async getSales(
    @Request() req,
    @Query('startDate') startDateString: string,
    @Query('endDate') endDateString: string,
  ) {
    const businessId = req.user.businessId;
    const startDate = startDateString
      ? new Date(startDateString)
      : new Date(new Date().setMonth(new Date().getMonth() - 1));
    const endDate = endDateString ? new Date(endDateString) : new Date();

    return this.reportsService.getSales(businessId, startDate, endDate);
  }

  @Get('statement/pdf')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  async downloadStatement(
    @Request() req,
    @Res() res: Response,
    @Query('partyId') partyId?: string,
    @Query('startDate') startDateString?: string,
    @Query('endDate') endDateString?: string,
  ) {
    const businessId = req.user.businessId;
    const startDate = startDateString
      ? new Date(startDateString)
      : new Date(new Date().setMonth(new Date().getMonth() - 1));
    const endDate = endDateString ? new Date(endDateString) : new Date();

    const entries = await this.reportsService.getLedgerEntries(
      businessId,
      partyId,
      startDate,
      endDate,
    );

    // Determine party if filtered
    let party: any = null;
    if (entries.length > 0 && partyId) {
      party = entries[0].party;
    }

    // Scoped to the caller's own (membership-verified) business — never the
    // raw businessId string, which previously printed on every statement.
    const business = await this.businessRepository.findOne({
      where: { id: businessId },
    });
    const businessName = business?.name?.trim() || 'Khata Book Business';

    const pdfBuffer = await this.pdfService.generateBusinessStatement(
      businessName,
      party,
      entries,
      startDate,
      endDate,
    );

    res.set({
      'Content-Type': 'application/pdf',
      'Content-Disposition': `attachment; filename="statement-${Date.now()}.pdf"`,
      'Content-Length': pdfBuffer.length,
    });

    res.end(pdfBuffer);
  }
}
