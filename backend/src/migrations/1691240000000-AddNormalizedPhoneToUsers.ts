import { MigrationInterface, QueryRunner } from "typeorm";

export class AddNormalizedPhoneToUsers1691240000000 implements MigrationInterface {
    name = 'AddNormalizedPhoneToUsers1691240000000'

    public async up(queryRunner: QueryRunner): Promise<void> {
        // Add column if it doesn't already exist
        await queryRunner.query(`ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "normalized_phone" character varying`);
        // Populate normalized_phone by stripping non-digits from phone_number
        await queryRunner.query(`UPDATE "users" SET "normalized_phone" = regexp_replace(phone_number, '\\D', '', 'g') WHERE "normalized_phone" IS NULL`);
        // Create unique index on normalized_phone for non-null values (idempotent)
        await queryRunner.query(`DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relname = 'UQ_users_normalized_phone') THEN
                CREATE UNIQUE INDEX "UQ_users_normalized_phone" ON "users" ("normalized_phone") WHERE "normalized_phone" IS NOT NULL;
            END IF;
        END $$;`);
    }

    public async down(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`DROP INDEX IF EXISTS "UQ_users_normalized_phone"`);
        await queryRunner.query(`ALTER TABLE "users" DROP COLUMN IF EXISTS "normalized_phone"`);
    }

}
