import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Optional per-party interest configuration (see Party entity doc). Every
 * column defaults to "interest off" so this is non-destructive: no existing
 * party starts accruing anything, and no existing query, balance or ledger
 * calculation is touched — interest is purely additive metadata.
 *
 * IF NOT EXISTS / IF EXISTS throughout, following
 * AddBusinessProfileFields1786557406269's precedent, so a re-run (or a run
 * against a environment where this was already partially applied) is a
 * no-op instead of an error.
 */
export class AddPartyInterestFields1786700000000
  implements MigrationInterface
{
  name = 'AddPartyInterestFields1786700000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "parties" ADD COLUMN IF NOT EXISTS "interest_enabled" boolean NOT NULL DEFAULT false`,
    );
    await queryRunner.query(
      `ALTER TABLE "parties" ADD COLUMN IF NOT EXISTS "interest_rate" numeric(8,3) NOT NULL DEFAULT 0`,
    );
    await queryRunner.query(
      `ALTER TABLE "parties" ADD COLUMN IF NOT EXISTS "interest_period_value" integer NOT NULL DEFAULT 0`,
    );
    await queryRunner.query(
      `ALTER TABLE "parties" ADD COLUMN IF NOT EXISTS "interest_period_unit" varchar(10) NOT NULL DEFAULT 'DAYS'`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "parties" DROP COLUMN IF EXISTS "interest_period_unit"`,
    );
    await queryRunner.query(
      `ALTER TABLE "parties" DROP COLUMN IF EXISTS "interest_period_value"`,
    );
    await queryRunner.query(
      `ALTER TABLE "parties" DROP COLUMN IF EXISTS "interest_rate"`,
    );
    await queryRunner.query(
      `ALTER TABLE "parties" DROP COLUMN IF EXISTS "interest_enabled"`,
    );
  }
}
