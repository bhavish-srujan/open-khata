import { ConfigService } from '@nestjs/config';
import { selectStorageDriver } from './storage.module';
import { S3StorageService } from './s3-storage.service';
import { LocalStorageService } from './local-storage.service';

/**
 * Which storage driver a deployment gets.
 *
 * Receipts were written to the container filesystem, which Render and Fly
 * destroy on every deploy, so the database row outlived the file it pointed at.
 * The property that matters here is that production cannot quietly fall back to
 * that — it must refuse to start instead.
 */
describe('storage driver selection', () => {
  // Called directly rather than through a Nest container: this is a rule about
  // configuration, and it should be verifiable without booting anything.
  const build = (env: Record<string, string | undefined>) =>
    selectStorageDriver({ get: (k: string) => env[k] } as unknown as ConfigService);

  const S3_ENV = {
    S3_BUCKET: 'receipts',
    S3_ACCESS_KEY_ID: 'key',
    S3_SECRET_ACCESS_KEY: 'secret',
  };

  it('uses object storage when it is configured', () => {
    expect(build({ ...S3_ENV, NODE_ENV: 'production' })).toBeInstanceOf(
      S3StorageService,
    );
  });

  it('falls back to local disk in development', () => {
    expect(build({ NODE_ENV: 'development' })).toBeInstanceOf(LocalStorageService);
  });

  it('refuses to start in production without object storage', () => {
    // Silently accepting uploads that the next deploy destroys is worse than
    // failing to boot.
    expect(() => build({ NODE_ENV: 'production' })).toThrow(
      /Object storage is required in production/,
    );
  });

  it.each([
    ['bucket only', { S3_BUCKET: 'receipts' }],
    ['missing secret', { S3_BUCKET: 'receipts', S3_ACCESS_KEY_ID: 'key' }],
    ['missing bucket', { S3_ACCESS_KEY_ID: 'key', S3_SECRET_ACCESS_KEY: 'secret' }],
  ])('refuses a half-configured bucket (%s), even in development', (_label, env) => {
    // Partial configuration is a deployment mistake, not a request for disk.
    expect(() => build({ ...env, NODE_ENV: 'development' })).toThrow(
      /partially configured/,
    );
  });

  it('names the missing settings so the mistake is obvious', () => {
    expect(() => build({ S3_BUCKET: 'receipts', NODE_ENV: 'development' })).toThrow(
      /S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY/,
    );
  });

  it('treats blank values as unset rather than configured', () => {
    expect(() =>
      build({
        S3_BUCKET: '  ',
        S3_ACCESS_KEY_ID: '',
        S3_SECRET_ACCESS_KEY: '',
        NODE_ENV: 'production',
      }),
    ).toThrow(/Object storage is required in production/);
  });
});
