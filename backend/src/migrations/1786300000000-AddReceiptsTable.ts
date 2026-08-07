import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Ownership records for uploaded receipts.
 *
 * Uploads previously had no association to a business, and the files were
 * served by an unauthenticated static handler, so any URL holder could read
 * another business's financial documents. Authorization needs this row.
 *
 * Idempotent, in keeping with the rest of the set.
 */
export class AddReceiptsTable1786300000000 implements MigrationInterface {
  name = 'AddReceiptsTable1786300000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`CREATE TABLE IF NOT EXISTS "receipts" (
      "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
      "business_id" uuid NOT NULL,
      "uploaded_by" uuid NOT NULL,
      "filename" character varying NOT NULL,
      "mime_type" character varying NOT NULL,
      "size_bytes" integer NOT NULL,
      "created_at" TIMESTAMP NOT NULL DEFAULT now(),
      CONSTRAINT "PK_receipts_id" PRIMARY KEY ("id")
    )`);

    await queryRunner.query(`DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'FK_receipts_business') THEN
        ALTER TABLE "receipts"
          ADD CONSTRAINT "FK_receipts_business"
          FOREIGN KEY ("business_id") REFERENCES "businesses"("id")
          ON DELETE NO ACTION ON UPDATE NO ACTION;
      END IF;
    END $$;`);

    // Every read is scoped by business, so the lookup is always (id, business_id).
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_receipts_business" ON "receipts" ("business_id")`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_receipts_business"`);
    await queryRunner.query(
      `ALTER TABLE "receipts" DROP CONSTRAINT IF EXISTS "FK_receipts_business"`,
    );
    await queryRunner.query(`DROP TABLE IF EXISTS "receipts"`);
  }
}
