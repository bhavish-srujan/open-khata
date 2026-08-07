import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { UploadsController } from './uploads.controller';
import { UploadsService } from './uploads.service';
import { Receipt } from './receipt.entity';

@Module({
  imports: [TypeOrmModule.forFeature([Receipt])],
  controllers: [UploadsController],
  providers: [UploadsService],
  exports: [UploadsService],
})
export class UploadsModule {}
