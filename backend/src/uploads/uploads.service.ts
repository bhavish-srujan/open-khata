import { ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { existsSync } from 'fs';
import { join } from 'path';
import { Receipt } from './receipt.entity';
import { RECEIPTS_DIR } from './uploads.config';

/** Per-business ceiling, so one tenant cannot exhaust the shared disk. */
const MAX_RECEIPTS_PER_BUSINESS = 2000;

@Injectable()
export class UploadsService {
  constructor(
    @InjectRepository(Receipt)
    private readonly receiptRepository: Repository<Receipt>,
  ) {}

  /**
   * Records ownership for a freshly stored file.
   *
   * Enforces a per-business ceiling. The size limit alone bounds one request,
   * not the total: with local disk and no quota, a single business could fill
   * the volume and take the service down for everyone.
   */
  async record(
    file: Express.Multer.File,
    businessId: string,
    uploadedBy: string,
  ): Promise<Receipt> {
    const held = await this.receiptRepository.count({ where: { businessId } });
    if (held >= MAX_RECEIPTS_PER_BUSINESS) {
      throw new ForbiddenException(
        `Receipt limit reached (${MAX_RECEIPTS_PER_BUSINESS}). Delete older receipts before uploading more.`,
      );
    }

    const receipt = this.receiptRepository.create({
      businessId,
      uploadedBy,
      filename: file.filename,
      mimeType: file.mimetype,
      sizeBytes: file.size,
    });
    return this.receiptRepository.save(receipt);
  }

  /**
   * Resolves a receipt to an absolute path, but only for the business that owns
   * it.
   *
   * A receipt belonging to another business is reported as not found rather
   * than forbidden. 403 would confirm that the id exists, turning this endpoint
   * into an existence oracle across tenants; the caller cannot distinguish
   * "never existed" from "not yours", which is the intent.
   */
  async resolveOwnedFile(
    id: string,
    businessId: string,
  ): Promise<{ receipt: Receipt; absolutePath: string }> {
    const receipt = await this.receiptRepository.findOne({
      where: { id, businessId },
    });

    if (!receipt) {
      throw new NotFoundException('Receipt not found');
    }

    // filename is generated server-side by multer and never accepted from the
    // client, so this join cannot escape RECEIPTS_DIR.
    const absolutePath = join(RECEIPTS_DIR, receipt.filename);

    if (!existsSync(absolutePath)) {
      // The row outlived the file. Expected on ephemeral hosting, where a
      // redeploy wipes the disk but leaves the database intact.
      throw new NotFoundException('Receipt file is no longer available');
    }

    return { receipt, absolutePath };
  }
}
