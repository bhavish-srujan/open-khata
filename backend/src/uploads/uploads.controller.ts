import {
  Controller,
  Post,
  UseInterceptors,
  UploadedFile,
  UseGuards,
  BadRequestException,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { diskStorage } from 'multer';
import { extname } from 'path';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RECEIPTS_DIR } from './uploads.config';

// Receipts are financial documents. This endpoint previously accepted uploads
// from anyone who could reach the port.
@UseGuards(JwtAuthGuard)
@Controller('uploads')
export class UploadsController {

  @Post('receipt')
  @UseInterceptors(FileInterceptor('file', {
    storage: diskStorage({
      destination: RECEIPTS_DIR,
      filename: (req, file, cb) => {
        // Generate a random unique string for filename
        const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
        const ext = extname(file.originalname);
        cb(null, `receipt-${uniqueSuffix}${ext}`);
      }
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
    }
  }))
  uploadReceipt(@UploadedFile() file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException('No file uploaded');
    }
    // Return the URL path where the file can be accessed.
    // Assuming we will configure static serving for the /uploads folder in main.ts
    return {
      message: 'File uploaded successfully',
      url: `/uploads/receipts/${file.filename}`
    };
  }
}
