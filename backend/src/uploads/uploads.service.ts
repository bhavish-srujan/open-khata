import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { randomUUID } from 'node:crypto';
import { Receipt } from './receipt.entity';
import { StorageService } from '../storage/storage.interface';
import { detectMime, extensionFor } from './file-type';

/** Per-business ceiling, so one tenant cannot exhaust shared storage. */
const MAX_RECEIPTS_PER_BUSINESS = 2000;

/** How long a download link stays valid. Long enough to fetch, short enough
 *  that a leaked URL is of little use. */
export const DOWNLOAD_URL_TTL_SECONDS = 120;

@Injectable()
export class UploadsService {
  constructor(
    @InjectRepository(Receipt)
    private readonly receiptRepository: Repository<Receipt>,
    private readonly storage: StorageService,
  ) {}

  /**
   * Storage key for a receipt.
   *
   * Namespaced by business so a key cannot be guessed across tenants and an
   * operator can see at a glance which objects belong to whom. Always generated
   * here — the client never supplies any part of a path.
   */
  private buildKey(businessId: string, extension: string): string {
    return `receipts/${businessId}/${randomUUID()}${extension}`;
  }

  /**
   * Stores an uploaded file and records who owns it.
   *
   * The declared Content-Type is ignored in favour of the actual leading bytes:
   * the client controls the former, and the extension filter alone allowed any
   * content through under an accepted name.
   */
  async store(
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

    const detected = detectMime(file.buffer);
    if (!detected) {
      throw new BadRequestException(
        'File contents are not a JPEG, PNG or PDF, whatever the file name says.',
      );
    }

    const key = this.buildKey(businessId, extensionFor(detected));
    const stored = await this.storage.put(key, file.buffer, detected);

    return this.receiptRepository.save(
      this.receiptRepository.create({
        businessId,
        uploadedBy,
        filename: key,
        mimeType: detected,
        sizeBytes: stored.size,
      }),
    );
  }

  /**
   * Resolves a receipt for the business that owns it.
   *
   * A receipt belonging to another business is reported as not found rather
   * than forbidden: 403 would confirm the id exists, turning this into a
   * cross-tenant existence oracle.
   */
  async findOwned(id: string, businessId: string): Promise<Receipt> {
    const receipt = await this.receiptRepository.findOne({
      where: { id, businessId },
    });

    if (!receipt) {
      throw new NotFoundException('Receipt not found');
    }

    return receipt;
  }

  /**
   * A short-lived direct download link, minted only after ownership is checked.
   *
   * Null when the driver cannot sign (local disk), telling the caller to stream
   * the bytes through the API instead.
   */
  async getDownloadUrl(id: string, businessId: string): Promise<string | null> {
    const receipt = await this.findOwned(id, businessId);

    if (!(await this.storage.exists(receipt.filename))) {
      // The row outlived the object. Expected for anything uploaded before
      // object storage, when a redeploy wiped the container disk.
      throw new NotFoundException('Receipt file is no longer available');
    }

    return this.storage.getSignedUrl(receipt.filename, DOWNLOAD_URL_TTL_SECONDS);
  }

  /** Bytes for the streaming fallback, ownership already established. */
  async readOwned(id: string, businessId: string): Promise<{ receipt: Receipt; body: Buffer }> {
    const receipt = await this.findOwned(id, businessId);
    return { receipt, body: await this.storage.get(receipt.filename) };
  }
}
