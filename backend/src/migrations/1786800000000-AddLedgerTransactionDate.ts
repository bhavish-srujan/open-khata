import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Adds the user-controlled business date to ledger entries.
 *
 * Separate from created_at by design: created_at records when the app wrote
 * the row, transaction_date records when the money actually moved. Entering
 * yesterday's sale today must land in yesterday's collection while still
 * showing an honest creation timestamp.
 *
 * Existing rows are backfilled from created_at rather than defaulted to now.
 * That reproduces exactly the behaviour those rows already had when created_at
 * was the only date available, so no historical daily/weekly/monthly figure
 * changes when this ships. Defaulting to now() instead would have silently
 * moved every past transaction into the deploy date.
 *
 * Additive and idempotent: no column is dropped, no row is deleted, and the
 * whole migration re-runs safely.
 */
export class AddLedgerTransactionDate1786800000000
  implements MigrationInterface
{
  name = 'AddLedgerTransactionDate1786800000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    // Nullable first so the backfill decides every existing row's value,
    // rather than the DEFAULT stamping deploy-time onto them.
    await queryRunner.query(
      `ALTER TABLE "ledger_entries" ADD COLUMN IF NOT EXISTS "transaction_date" TIMESTAMP WITH TIME ZONE`,
    );

    await queryRunner.query(
      `UPDATE "ledger_entries" SET "transaction_date" = "createdAt" WHERE "transaction_date" IS NULL`,
    );

    await queryRunner.query(
      `ALTER TABLE "ledger_entries" ALTER COLUMN "transaction_date" SET DEFAULT now()`,
    );
    await queryRunner.query(
      `ALTER TABLE "ledger_entries" ALTER COLUMN "transaction_date" SET NOT NULL`,
    );

    // Every reporting query now filters and orders on this column.
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_ledger_entries_transaction_date" ON "ledger_entries" ("transaction_date")`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `DROP INDEX IF EXISTS "IDX_ledger_entries_transaction_date"`,
    );
    await queryRunner.query(
      `ALTER TABLE "ledger_entries" DROP COLUMN IF EXISTS "transaction_date"`,
    );
  }
}
