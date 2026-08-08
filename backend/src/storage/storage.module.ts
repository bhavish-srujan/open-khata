import { Global, Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { StorageService } from './storage.interface';
import { S3StorageService } from './s3-storage.service';
import { LocalStorageService } from './local-storage.service';

/** Every S3 setting that must be present together for the driver to work. */
const REQUIRED_S3_VARS = [
  'S3_BUCKET',
  'S3_ACCESS_KEY_ID',
  'S3_SECRET_ACCESS_KEY',
] as const;

/**
 * Chooses the driver for a given configuration.
 *
 * Exported so the decision can be tested directly: it is the rule that keeps
 * production off an ephemeral disk, and it should not need a running Nest
 * container to verify.
 */
export function selectStorageDriver(config: ConfigService): StorageService {
    const configured = REQUIRED_S3_VARS.filter((v) =>
      config.get<string>(v)?.trim(),
    );
    const isProduction = config.get<string>('NODE_ENV') === 'production';

    if (configured.length === REQUIRED_S3_VARS.length) {
      return new S3StorageService(config);
    }

    // A half-configured bucket is a deployment mistake, not a request to
    // fall back to disk.
    if (configured.length > 0) {
      const missing = REQUIRED_S3_VARS.filter((v) => !configured.includes(v));
      throw new Error(
        `Object storage is partially configured. Missing: ${missing.join(', ')}`,
      );
    }

    if (isProduction) {
      // Container filesystems here are ephemeral. Silently accepting
      // uploads that a redeploy destroys is worse than refusing to boot.
      throw new Error(
        'Object storage is required in production. Set S3_BUCKET, ' +
          'S3_ACCESS_KEY_ID and S3_SECRET_ACCESS_KEY.',
      );
    }

  return new LocalStorageService();
}

@Global()
@Module({
  providers: [
    {
      provide: StorageService,
      inject: [ConfigService],
      useFactory: selectStorageDriver,
    },
  ],
  exports: [StorageService],
})
export class StorageModule {}
