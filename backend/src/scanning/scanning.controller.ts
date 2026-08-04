import { Controller, Post, UseInterceptors, UploadedFile, UseGuards, BadRequestException } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { ScanningService } from './scanning.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('api/v1/scanning')
export class ScanningController {
  constructor(private readonly scanningService: ScanningService) {}

  @Post('extract')
  @UseInterceptors(FileInterceptor('image'))
  async extractLedger(@UploadedFile() file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException('No image file provided');
    }
    return this.scanningService.extractLedgerData(file.buffer, file.mimetype);
  }
}
