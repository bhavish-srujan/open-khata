import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { PartyController } from './party.controller';
import { PartyService } from './party.service';
import { Party } from './party.entity';

@Module({
  imports: [TypeOrmModule.forFeature([Party])],
  controllers: [PartyController],
  providers: [PartyService],
  exports: [PartyService],
})
export class PartyModule {}
