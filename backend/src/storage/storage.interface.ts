/**
 * Somewhere to keep uploaded files.
 *
 * Receipts were written to the container's local disk, which on Render and Fly
 * is destroyed on every deploy, restart and crash — the database row outlived
 * the file it pointed at. This interface exists so the production driver can be
 * object storage while local development keeps using the disk.
 */
export interface StoredObject {
  /** Opaque storage key. Generated server-side; never accepted from a client. */
  key: string;
  size: number;
  contentType: string;
}

export abstract class StorageService {
  /** Persists bytes under a server-generated key. */
  abstract put(
    key: string,
    body: Buffer,
    contentType: string,
  ): Promise<StoredObject>;

  /**
   * A short-lived URL the client can read the object from directly.
   *
   * Returns null when the driver cannot issue one (local disk), so the caller
   * falls back to streaming through the API.
   */
  abstract getSignedUrl(key: string, expiresInSeconds: number): Promise<string | null>;

  /** Bytes, for the streaming fallback. */
  abstract get(key: string): Promise<Buffer>;

  abstract delete(key: string): Promise<void>;

  abstract exists(key: string): Promise<boolean>;
}
