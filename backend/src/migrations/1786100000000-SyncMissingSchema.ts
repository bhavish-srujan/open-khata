import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Brings the migration set back in line with the entities.
 *
 * The app has been running with `synchronize: true` (NODE_ENV !== production),
 * so schemas were being mutated implicitly and the migrations fell behind:
 *
 *   - users lacked currentOtp, otpExpiry, role and sessionVersion, all of which
 *     the auth flow writes on every OTP request and login.
 *   - ledger_entries lacked receiptUrl.
 *   - items, bills and audit_logs had no migration at all. audit_logs matters
 *     most: the audit subscriber writes inside the caller's transaction, so a
 *     missing table there fails the ledger write itself.
 *
 * Every statement is idempotent. Databases built by synchronize already have
 * some or all of this, and must be able to adopt the migration set without
 * being torn down.
 */
export class SyncMissingSchema1786100000000 implements MigrationInterface {
  name = 'SyncMissingSchema1786100000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`);

    // ── users: columns added since the initial schema ──────────────────────
    await queryRunner.query(`DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'users_role_enum') THEN
        CREATE TYPE "users_role_enum" AS ENUM ('OWNER', 'MANAGER', 'CASHIER');
      END IF;
    END $$;`);
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "currentOtp" character varying`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "otpExpiry" TIMESTAMP`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "role" "users_role_enum" NOT NULL DEFAULT 'OWNER'`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "sessionVersion" integer NOT NULL DEFAULT 1`,
    );

    // ── ledger_entries ─────────────────────────────────────────────────────
    await queryRunner.query(
      `ALTER TABLE "ledger_entries" ADD COLUMN IF NOT EXISTS "receiptUrl" character varying`,
    );

    // ── items ──────────────────────────────────────────────────────────────
    await queryRunner.query(`CREATE TABLE IF NOT EXISTS "items" (
      "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
      "name" character varying NOT NULL,
      "sku" character varying,
      "sellingPrice" numeric(14,2) NOT NULL DEFAULT '0',
      "purchasePrice" numeric(14,2) NOT NULL DEFAULT '0',
      "currentStock" numeric(14,2) NOT NULL DEFAULT '0',
      "lowStockThreshold" numeric(14,2) NOT NULL DEFAULT '5',
      "business_id" uuid NOT NULL,
      "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
      "updatedAt" TIMESTAMP NOT NULL DEFAULT now(),
      CONSTRAINT "PK_items_id" PRIMARY KEY ("id")
    )`);

    // ── bills ──────────────────────────────────────────────────────────────
    // Note the camelCase column names: the Bill entity declares businessId /
    // partyId / ledgerEntryId without a snake_case override, unlike its siblings.
    await queryRunner.query(`CREATE TABLE IF NOT EXISTS "bills" (
      "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
      "businessId" uuid NOT NULL,
      "partyId" uuid NOT NULL,
      "totalAmount" numeric(10,2) NOT NULL,
      "items" jsonb,
      "ledgerEntryId" uuid,
      "isGst" boolean NOT NULL DEFAULT false,
      "receiptUrl" character varying,
      "created_at" TIMESTAMP NOT NULL DEFAULT now(),
      "updated_at" TIMESTAMP NOT NULL DEFAULT now(),
      CONSTRAINT "PK_bills_id" PRIMARY KEY ("id")
    )`);

    // ── audit_logs ─────────────────────────────────────────────────────────
    await queryRunner.query(`CREATE TABLE IF NOT EXISTS "audit_logs" (
      "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
      "action" character varying(50) NOT NULL,
      "entityName" character varying NOT NULL,
      "entityId" character varying NOT NULL,
      "performed_by" character varying NOT NULL,
      "previousState" jsonb,
      "newState" jsonb,
      "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
      CONSTRAINT "PK_audit_logs_id" PRIMARY KEY ("id")
    )`);

    // ── foreign keys ───────────────────────────────────────────────────────
    const addForeignKey = async (
      constraint: string,
      table: string,
      column: string,
      referenced: string,
    ) => {
      await queryRunner.query(`DO $$ BEGIN
        IF NOT EXISTS (
          SELECT 1 FROM pg_constraint WHERE conname = '${constraint}'
        ) THEN
          ALTER TABLE "${table}"
            ADD CONSTRAINT "${constraint}"
            FOREIGN KEY ("${column}") REFERENCES "${referenced}"("id")
            ON DELETE NO ACTION ON UPDATE NO ACTION;
        END IF;
      END $$;`);
    };

    await addForeignKey('FK_items_business', 'items', 'business_id', 'businesses');
    await addForeignKey('FK_bills_business', 'bills', 'businessId', 'businesses');
    await addForeignKey('FK_bills_party', 'bills', 'partyId', 'parties');
    await addForeignKey(
      'FK_bills_ledger_entry',
      'bills',
      'ledgerEntryId',
      'ledger_entries',
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`ALTER TABLE "bills" DROP CONSTRAINT IF EXISTS "FK_bills_ledger_entry"`);
    await queryRunner.query(`ALTER TABLE "bills" DROP CONSTRAINT IF EXISTS "FK_bills_party"`);
    await queryRunner.query(`ALTER TABLE "bills" DROP CONSTRAINT IF EXISTS "FK_bills_business"`);
    await queryRunner.query(`ALTER TABLE "items" DROP CONSTRAINT IF EXISTS "FK_items_business"`);

    await queryRunner.query(`DROP TABLE IF EXISTS "audit_logs"`);
    await queryRunner.query(`DROP TABLE IF EXISTS "bills"`);
    await queryRunner.query(`DROP TABLE IF EXISTS "items"`);

    await queryRunner.query(`ALTER TABLE "ledger_entries" DROP COLUMN IF EXISTS "receiptUrl"`);

    await queryRunner.query(`ALTER TABLE "users" DROP COLUMN IF EXISTS "sessionVersion"`);
    await queryRunner.query(`ALTER TABLE "users" DROP COLUMN IF EXISTS "role"`);
    await queryRunner.query(`ALTER TABLE "users" DROP COLUMN IF EXISTS "otpExpiry"`);
    await queryRunner.query(`ALTER TABLE "users" DROP COLUMN IF EXISTS "currentOtp"`);
    await queryRunner.query(`DROP TYPE IF EXISTS "users_role_enum"`);
  }
}
