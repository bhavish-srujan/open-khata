import {
  BadRequestException,
  Controller,
  Get,
  Param,
  ParseUUIDPipe,
  Post,
  Req,
  Res,
  UploadedFile,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import type { Response } from 'express';
import { FileInterceptor } from '@nestjs/platform-express';
import { memoryStorage } from 'multer';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { UploadsService } from './uploads.service';

/** Matches the 5MB cap enforced by multer below. */
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

// Receipts are financial documents. This controller had no guard at all, and
// the files were separately exposed by an unauthenticated static handler, so
// any URL holder could read another business's documents.
@UseGuards(JwtAuthGuard)
@Controller('uploads')
export class UploadsController {
  constructor(private readonly uploadsService: UploadsService) {}

  @Post('receipt')
  @UseInterceptors(
    FileInterceptor('file', {
      // Memory, not disk: the bytes go straight to object storage, and nothing
      // is left behind on a filesystem that a redeploy destroys. Bounded by the
      // size limit below.
      storage: memoryStorage(),
      limits: {
        fileSize: MAX_UPLOAD_BYTES,
        // One file per request, and few parts: otherwise a caller can attach
        // many files and bypass the size limit in aggregate.
        files: 1,
        parts: 5,
      },
    }),
  )
  async uploadReceipt(@UploadedFile() file: Express.Multer.File, @Req() req) {
    if (!file) {
      throw new BadRequestException('No file uploaded');
    }

    // The real type is decided from the bytes in the service, not from the
    // client-supplied Content-Type or the file name.
    const receipt = await this.uploadsService.store(
      file,
      req.user.businessId,
      req.user.sub,
    );

    return {
      message: 'File uploaded successfully',
      id: receipt.id,
      url: `/uploads/receipts/${receipt.id}`,
      mimeType: receipt.mimeType,
      sizeBytes: receipt.sizeBytes,
    };
  }

  /**
   * Hands back the receipt to the business that owns it.
   *
   * With object storage this is a redirect to a short-lived signed URL, so the
   * bytes travel from the bucket to the client and never through this process.
   * On local disk, where nothing can be signed, the file is streamed instead.
   *
   * ParseUUIDPipe rejects anything that is not a UUID, so a storage path can
   * never be addressed directly.
   */
  @Get('receipts/:id')
  async getReceipt(
    @Param('id', new ParseUUIDPipe()) id: string,
    @Req() req,
    @Res() res: Response,
  ) {
    const signedUrl = await this.uploadsService.getDownloadUrl(
      id,
      req.user.businessId,
    );

    // Financial documents must not be retained by shared caches, and the
    // redirect target is a credential in itself.
    res.set({ 'Cache-Control': 'private, no-store' });

    if (signedUrl) {
      return res.redirect(302, signedUrl);
    }

    const { receipt, body } = await this.uploadsService.readOwned(
      id,
      req.user.businessId,
    );

    res.set({
      'Content-Type': receipt.mimeType,
      // Always an attachment, never inline: content that sniffs as HTML would
      // otherwise run in this origin.
      'Content-Disposition': `attachment; filename="receipt-${receipt.id}"`,
      'X-Content-Type-Options': 'nosniff',
      'Content-Length': String(body.length),
    });
    return res.end(body);
  }
}
