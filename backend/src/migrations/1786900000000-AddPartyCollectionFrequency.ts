import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Persists the party's Daily/Weekly/Monthly collection bucket.
 *
 * This classification only ever existed in the Android client's Room
 * database — it was never sent here, so a device restoring from the server
 * had nothing to restore it from and every party came back as DAILY,
 * emptying the Weekly and Monthly collections.
 *
 * DEFAULT 'DAILY' is the honest value for existing rows rather than a guess:
 * a row the server never knew a frequency for already behaved as DAILY
 * everywhere, so this changes no observable behaviour for existing data. The
 * true value for parties classified Weekly/Monthly before this shipped still
 * lives on whichever device created them, and the client pushes it up via
 * PATCH /parties/:id/collection-frequency — it is deliberately not guessed at
 * here, because inferring it from dates or activity is exactly the mistake
 * that would silently reclassify someone's books.
 *
 * Additive and idempotent: nothing is dropped, no row is deleted, and a
 * re-run is a no-op.
 */
export class AddPartyCollectionFrequency1786900000000
  implements MigrationInterface
{
  name = 'AddPartyCollectionFrequency1786900000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "parties" ADD COLUMN IF NOT EXISTS "collection_frequency" varchar(10) NOT NULL DEFAULT 'DAILY'`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "parties" DROP COLUMN IF EXISTS "collection_frequency"`,
    );
  }
}
