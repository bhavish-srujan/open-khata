import { Injectable, Logger, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
  S3Client,
  PutObjectCommand,
  GetObjectCommand,
  DeleteObjectCommand,
  HeadObjectCommand,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { StorageService, StoredObject } from './storage.interface';

/**
 * S3-compatible object storage: AWS S3, Cloudflare R2, MinIO.
 *
 * Objects are private. Reading goes through a presigned URL minted only after
 * the caller's ownership has been checked, so the bucket itself never needs to
 * be public and a leaked key alone grants nothing.
 */
@Injectable()
export class S3StorageService extends StorageService {
  private readonly logger = new Logger(S3StorageService.name);
  private readonly client: S3Client;
  private readonly bucket: string;
  /**
   * Server-side encryption is provider-specific: S3 takes AES256, R2 encrypts
   * at rest regardless and needs no header, and MinIO rejects the header unless
   * KMS is configured. Opt in per deployment rather than hardcoding one.
   */
  private readonly serverSideEncryption?: string;

  constructor(private readonly config: ConfigService) {
    super();

    this.bucket = this.config.get<string>('S3_BUCKET')!;
    const endpoint = this.config.get<string>('S3_ENDPOINT');
    this.serverSideEncryption =
      this.config.get<string>('S3_SERVER_SIDE_ENCRYPTION')?.trim() || undefined;

    this.client = new S3Client({
      region: this.config.get<string>('S3_REGION') || 'auto',
      // R2 and MinIO need an explicit endpoint; plain AWS does not.
      ...(endpoint ? { endpoint } : {}),
      // Path style for MinIO and anything else without virtual-host addressing.
      forcePathStyle: this.config.get<string>('S3_FORCE_PATH_STYLE') === 'true',
      credentials: {
        accessKeyId: this.config.get<string>('S3_ACCESS_KEY_ID')!,
        secretAccessKey: this.config.get<string>('S3_SECRET_ACCESS_KEY')!,
      },
    });
  }

  async put(key: string, body: Buffer, contentType: string): Promise<StoredObject> {
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: key,
        Body: body,
        ContentType: contentType,
        // Never inline: a file that sniffs as HTML would otherwise execute in
        // the origin of whatever domain serves it.
        ContentDisposition: 'attachment',
        ...(this.serverSideEncryption
          ? { ServerSideEncryption: this.serverSideEncryption as any }
          : {}),
      }),
    );

    return { key, size: body.length, contentType };
  }

  async getSignedUrl(key: string, expiresInSeconds: number): Promise<string> {
    return getSignedUrl(
      this.client,
      new GetObjectCommand({
        Bucket: this.bucket,
        Key: key,
        // Force a download even though the object is fetched directly.
        ResponseContentDisposition: 'attachment',
      }),
      { expiresIn: expiresInSeconds },
    );
  }

  async get(key: string): Promise<Buffer> {
    try {
      const res = await this.client.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: key }),
      );
      return Buffer.from(await res.Body!.transformToByteArray());
    } catch (error) {
      if (this.isMissing(error)) {
        throw new NotFoundException('Stored object is no longer available');
      }
      throw error;
    }
  }

  async delete(key: string): Promise<void> {
    await this.client.send(
      new DeleteObjectCommand({ Bucket: this.bucket, Key: key }),
    );
  }

  async exists(key: string): Promise<boolean> {
    try {
      await this.client.send(
        new HeadObjectCommand({ Bucket: this.bucket, Key: key }),
      );
      return true;
    } catch (error) {
      if (this.isMissing(error)) return false;
      throw error;
    }
  }

  private isMissing(error: any): boolean {
    return (
      error?.name === 'NoSuchKey' ||
      error?.name === 'NotFound' ||
      error?.$metadata?.httpStatusCode === 404
    );
  }
}
