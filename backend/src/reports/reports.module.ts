import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { ReportsController } from './reports.controller';
import { ReportsService } from './reports.service';
import { PdfService } from './pdf/pdf.service';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { Bill } from '../bills/entities/bill.entity';
import { Business } from '../business/business.entity';

@Module({
  imports: [TypeOrmModule.forFeature([LedgerEntry, Bill, Business])],
  controllers: [ReportsController],
  providers: [ReportsService, PdfService],
  exports: [ReportsService, PdfService],
})
export class ReportsModule {}
