import {
  Controller,
  Post,
  UseInterceptors,
  UploadedFile,
  UseGuards,
  BadRequestException,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { Throttle } from '@nestjs/throttler';
import { ScanningService } from './scanning.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

/** Generous for any realistically-compressed phone photo; a defense-in-depth
 *  cap independent of whatever the client claims to have already done — the
 *  Android client's own resize/compress step is not something this endpoint
 *  can trust unconditionally. */
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

@UseGuards(JwtAuthGuard)
@Controller('api/v1/scanning')
export class ScanningController {
  constructor(private readonly scanningService: ScanningService) {}

  /**
   * Each call is a paid Gemini API request, unlike the rest of the API, so it
   * carries a tighter limit than the 120/min global default. 10/min is loose
   * enough for someone genuinely scanning several collection sheets or
   * retrying a bad photo in one sitting, while bounding the cost of a script
   * looping this endpoint to a few hundred calls an hour instead of
   * thousands.
   */
  @Throttle({ default: { ttl: 60_000, limit: 10 } })
  @Post('extract')
  @UseInterceptors(
    FileInterceptor('image', { limits: { fileSize: MAX_IMAGE_BYTES } }),
  )
  async extractLedger(@UploadedFile() file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException(
        'No image file provided or image exceeds the 10MB limit',
      );
    }
    return this.scanningService.extractLedgerData(file.buffer, file.mimetype);
  }
}
