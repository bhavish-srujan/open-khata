import { ConfigService } from '@nestjs/config';
import { NotFoundException } from '@nestjs/common';
import { S3StorageService } from './s3-storage.service';

/**
 * The S3 driver against a real S3 API (MinIO from docker-compose).
 *
 * A mocked SDK would prove only that the code calls the functions it calls. The
 * things that actually break in object storage — a key that does not round
 * trip, a presigned URL that will not fetch, a missing object surfacing as a
 * 500 instead of a 404 — only show up against a real implementation.
 *
 * Skipped automatically when MinIO is not running, so the suite stays green on
 * a machine without docker.
 */
const ENDPOINT = process.env.TEST_S3_ENDPOINT || 'http://127.0.0.1:9000';
const BUCKET = process.env.TEST_S3_BUCKET || 'khatabook-receipts';

const settings: Record<string, string> = {
  S3_BUCKET: BUCKET,
  S3_ENDPOINT: ENDPOINT,
  S3_REGION: 'us-east-1',
  S3_FORCE_PATH_STYLE: 'true',
  S3_ACCESS_KEY_ID: process.env.TEST_S3_KEY || 'minioadmin',
  S3_SECRET_ACCESS_KEY: process.env.TEST_S3_SECRET || 'minioadmin',
};

const config = { get: (k: string) => settings[k] } as unknown as ConfigService;

async function minioReachable(): Promise<boolean> {
  try {
    const res = await fetch(`${ENDPOINT}/minio/health/live`, {
      signal: AbortSignal.timeout(2000),
    });
    return res.ok;
  } catch {
    return false;
  }
}

describe('S3StorageService against a real bucket', () => {
  let storage: S3StorageService;
  let available = false;
  const written: string[] = [];

  beforeAll(async () => {
    available = await minioReachable();
    if (!available) {
      console.warn('MinIO not reachable — skipping S3 integration tests');
      return;
    }
    storage = new S3StorageService(config);
  });

  afterAll(async () => {
    if (!available) return;
    for (const key of written) await storage.delete(key).catch(() => undefined);
  });

  const key = (name: string) => {
    const k = `receipts/test-business/${Date.now()}-${name}`;
    written.push(k);
    return k;
  };

  const itIfAvailable = (name: string, fn: () => Promise<void>) =>
    it(name, async () => {
      if (!available) return;
      await fn();
    });

  itIfAvailable('round-trips bytes unchanged', async () => {
    const k = key('roundtrip.png');
    const body = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4, 5]);

    const stored = await storage.put(k, body, 'image/png');
    expect(stored.size).toBe(body.length);

    const read = await storage.get(k);
    expect(read.equals(body)).toBe(true);
  });

  itIfAvailable('reports existence accurately', async () => {
    const k = key('exists.pdf');
    expect(await storage.exists(k)).toBe(false);

    await storage.put(k, Buffer.from('%PDF-1.4'), 'application/pdf');
    expect(await storage.exists(k)).toBe(true);
  });

  itIfAvailable('a missing object is not found rather than a server error', async () => {
    await expect(storage.get('receipts/none/missing.png')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });

  itIfAvailable('delete removes the object', async () => {
    const k = key('delete-me.png');
    await storage.put(k, Buffer.from('x'), 'image/png');

    await storage.delete(k);

    expect(await storage.exists(k)).toBe(false);
  });

  describe('presigned URLs', () => {
    itIfAvailable('a signed URL actually fetches the object', async () => {
      const k = key('signed.png');
      const body = Buffer.from('signed-content');
      await storage.put(k, body, 'image/png');

      const url = await storage.getSignedUrl(k, 60);
      const res = await fetch(url);

      expect(res.status).toBe(200);
      expect(Buffer.from(await res.arrayBuffer()).toString()).toBe('signed-content');
    });

    itIfAvailable('the object is not readable without a signature', async () => {
      const k = key('private.png');
      await storage.put(k, Buffer.from('secret'), 'image/png');

      // The bucket must not be public: an unsigned request must be refused.
      const res = await fetch(`${ENDPOINT}/${BUCKET}/${k}`);

      expect(res.status).toBeGreaterThanOrEqual(400);
      expect(await res.text()).not.toContain('secret');
    });

    itIfAvailable('a signed URL stops working once it expires', async () => {
      const k = key('expiring.png');
      await storage.put(k, Buffer.from('temporary'), 'image/png');

      const url = await storage.getSignedUrl(k, 1);
      await new Promise((r) => setTimeout(r, 1500));

      const res = await fetch(url);
      expect(res.status).toBeGreaterThanOrEqual(400);
    });

    itIfAvailable('a signature for one object does not unlock another', async () => {
      const mine = key('mine.png');
      const theirs = key('theirs.png');
      await storage.put(mine, Buffer.from('mine'), 'image/png');
      await storage.put(theirs, Buffer.from('theirs'), 'image/png');

      const url = await storage.getSignedUrl(mine, 60);
      // Swapping the key invalidates the signature it was computed over.
      const tampered = url.replace(encodeURIComponent(mine), encodeURIComponent(theirs))
        .replace(mine, theirs);

      const res = await fetch(tampered);
      expect(res.status).toBeGreaterThanOrEqual(400);
    });

    itIfAvailable('forces a download rather than inline rendering', async () => {
      const k = key('disposition.png');
      await storage.put(k, Buffer.from('<html>x</html>'), 'image/png');

      const res = await fetch(await storage.getSignedUrl(k, 60));

      // Content that sniffs as HTML must never render in a browser origin.
      expect(res.headers.get('content-disposition')).toContain('attachment');
    });
  });
});
