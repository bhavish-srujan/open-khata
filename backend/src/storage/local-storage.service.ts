import { Injectable, NotFoundException } from '@nestjs/common';
import { promises as fs } from 'fs';
import { dirname, join, resolve, sep } from 'path';
import { StorageService, StoredObject } from './storage.interface';
import { UPLOADS_ROOT } from '../uploads/uploads.config';

/**
 * Local disk, for development only.
 *
 * Deliberately not production storage: container filesystems on Render and Fly
 * are destroyed on every deploy, so the database row would outlive the file.
 * AppModule selects the S3 driver whenever S3_BUCKET is configured, and refuses
 * to run in production without it.
 */
@Injectable()
export class LocalStorageService extends StorageService {
  /**
   * Resolves a key under the uploads root, refusing anything that escapes it.
   *
   * Keys are server-generated, so traversal is not reachable today. The check
   * costs nothing and means a future path that does accept caller input cannot
   * turn into an arbitrary write.
   */
  private pathFor(key: string): string {
    const full = resolve(join(UPLOADS_ROOT, key));
    const root = resolve(UPLOADS_ROOT);
    if (full !== root && !full.startsWith(root + sep)) {
      throw new NotFoundException('Invalid storage key');
    }
    return full;
  }

  async put(key: string, body: Buffer, contentType: string): Promise<StoredObject> {
    const path = this.pathFor(key);
    await fs.mkdir(dirname(path), { recursive: true });
    await fs.writeFile(path, body);
    return { key, size: body.length, contentType };
  }

  /** No signing on disk; the caller streams through the API instead. */
  async getSignedUrl(): Promise<string | null> {
    return null;
  }

  async get(key: string): Promise<Buffer> {
    try {
      return await fs.readFile(this.pathFor(key));
    } catch {
      throw new NotFoundException('Stored object is no longer available');
    }
  }

  async delete(key: string): Promise<void> {
    await fs.rm(this.pathFor(key), { force: true });
  }

  async exists(key: string): Promise<boolean> {
    try {
      await fs.access(this.pathFor(key));
      return true;
    } catch {
      return false;
    }
  }
}
