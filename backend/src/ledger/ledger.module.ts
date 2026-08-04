import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { LedgerController } from './ledger.controller';
import { LedgerService } from './ledger.service';
import { LedgerEntry } from './ledger-entry.entity';
import { Party } from '../party/party.entity';

@Module({
  imports: [TypeOrmModule.forFeature([LedgerEntry, Party])],
  controllers: [LedgerController],
  providers: [LedgerService],
  exports: [LedgerService],
})
export class LedgerModule {}
