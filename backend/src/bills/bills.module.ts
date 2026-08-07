import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { BillsService } from './bills.service';
import { BillsController } from './bills.controller';
import { Bill } from './entities/bill.entity';
import { Party } from '../party/party.entity';
import { LedgerModule } from '../ledger/ledger.module';

@Module({
  imports: [TypeOrmModule.forFeature([Bill, Party]), LedgerModule],
  controllers: [BillsController],
  providers: [BillsService],
})
export class BillsModule {}
