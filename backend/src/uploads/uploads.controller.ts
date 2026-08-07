import {
  Controller,
  Get,
  Param,
  ParseUUIDPipe,
  Post,
  Req,
  Res,
  StreamableFile,
  UploadedFile,
  UseGuards,
  UseInterceptors,
  BadRequestException,
} from '@nestjs/common';
import type { Response } from 'express';
import { FileInterceptor } from '@nestjs/platform-express';
import { diskStorage } from 'multer';
import { createReadStream } from 'fs';
import { extname } from 'path';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { UploadsService } from './uploads.service';
import { RECEIPTS_DIR } from './uploads.config';

// Receipts are financial documents. This controller previously had no guard at
// all, and the files were additionally exposed by an unauthenticated static
// handler on /uploads/**, so any URL holder could read another business's
// documents. Both the write and the read now require a token, and the read is
// scoped to the owning business.
@UseGuards(JwtAuthGuard)
@Controller('uploads')
export class UploadsController {
  constructor(private readonly uploadsService: UploadsService) {}

  @Post('receipt')
  @UseInterceptors(
    FileInterceptor('file', {
      storage: diskStorage({
        destination: RECEIPTS_DIR,
        filename: (req, file, cb) => {
          // Generate a random unique string for filename
          const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
          const ext = extname(file.originalname);
          cb(null, `receipt-${uniqueSuffix}${ext}`);
        },
      }),
      limits: {
        fileSize: 5 * 1024 * 1024, // 5MB limit
      },
      fileFilter: (req, file, cb) => {
        // Allow only images and PDFs
        if (!file.mimetype.match(/\/(jpg|jpeg|png|pdf)$/)) {
          return cb(new BadRequestException('Only image and PDF files are allowed!'), false);
        }
        cb(null, true);
      },
    }),
  )
  async uploadReceipt(@UploadedFile() file: Express.Multer.File, @Req() req) {
    if (!file) {
      throw new BadRequestException('No file uploaded');
    }

    const receipt = await this.uploadsService.record(
      file,
      req.user.businessId,
      req.user.sub,
    );

    // The URL carries the ownership row's id, not the on-disk filename, so the
    // client never supplies a path.
    return {
      message: 'File uploaded successfully',
      id: receipt.id,
      url: `/uploads/receipts/${receipt.id}`,
    };
  }

  /**
   * Streams a receipt to the business that owns it.
   *
   * ParseUUIDPipe rejects anything that is not a UUID, so traversal sequences
   * never reach the lookup.
   */
  @Get('receipts/:id')
  async getReceipt(
    @Param('id', new ParseUUIDPipe()) id: string,
    @Req() req,
    @Res({ passthrough: true }) res: Response,
  ): Promise<StreamableFile> {
    const { receipt, absolutePath } = await this.uploadsService.resolveOwnedFile(
      id,
      req.user.businessId,
    );

    res.set({
      'Content-Type': receipt.mimeType,
      'Content-Disposition': `inline; filename="${receipt.filename}"`,
      // Financial documents must not be retained by shared caches.
      'Cache-Control': 'private, no-store',
    });

    return new StreamableFile(createReadStream(absolutePath));
  }
}
