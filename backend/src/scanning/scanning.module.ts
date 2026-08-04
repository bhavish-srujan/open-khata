import { Module } from '@nestjs/common';
import { ScanningController } from './scanning.controller';
import { ScanningService } from './scanning.service';

@Module({
  controllers: [ScanningController],
  providers: [ScanningService]
})
export class ScanningModule {}
