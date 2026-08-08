import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Ties users to businesses, with a role scoped to each.
 *
 * Role was a single global column on User, so it could not differ per business
 * and nothing associated a user with one. The staff endpoints consequently
 * returned every MANAGER and CASHIER in the database.
 *
 * Existing owners are backfilled from businesses.owner_id, so every business
 * that exists today keeps a member who can administer it. Without that backfill
 * the current six production users would lose access the moment role resolution
 * starts reading this table.
 */
export class AddBusinessMembersTable1786400000000 implements MigrationInterface {
  name = 'AddBusinessMembersTable1786400000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`CREATE TABLE IF NOT EXISTS "business_members" (
      "id" uuid NOT NULL DEFAULT uuid_generate_v4(),
      "business_id" uuid NOT NULL,
      "user_id" uuid NOT NULL,
      "role" character varying(20) NOT NULL,
      "created_at" TIMESTAMP NOT NULL DEFAULT now(),
      "updated_at" TIMESTAMP NOT NULL DEFAULT now(),
      CONSTRAINT "PK_business_members_id" PRIMARY KEY ("id")
    )`);

    // One membership per user per business; the service relies on this to make
    // repeated adds idempotent rather than duplicating a member.
    await queryRunner.query(`DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'UQ_business_members_business_user'
      ) THEN
        ALTER TABLE "business_members"
          ADD CONSTRAINT "UQ_business_members_business_user"
          UNIQUE ("business_id", "user_id");
      END IF;
    END $$;`);

    await queryRunner.query(`DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'FK_business_members_business') THEN
        ALTER TABLE "business_members"
          ADD CONSTRAINT "FK_business_members_business"
          FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE;
      END IF;
    END $$;`);

    await queryRunner.query(`DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'FK_business_members_user') THEN
        ALTER TABLE "business_members"
          ADD CONSTRAINT "FK_business_members_user"
          FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE;
      END IF;
    END $$;`);

    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_business_members_user" ON "business_members" ("user_id")`,
    );

    // Backfill: every existing business owner becomes an OWNER member.
    await queryRunner.query(`
      INSERT INTO "business_members" ("business_id", "user_id", "role")
      SELECT b."id", b."owner_id", 'OWNER'
      FROM "businesses" b
      WHERE NOT EXISTS (
        SELECT 1 FROM "business_members" m
        WHERE m."business_id" = b."id" AND m."user_id" = b."owner_id"
      )
    `);
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_business_members_user"`);
    await queryRunner.query(
      `ALTER TABLE "business_members" DROP CONSTRAINT IF EXISTS "FK_business_members_user"`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" DROP CONSTRAINT IF EXISTS "FK_business_members_business"`,
    );
    await queryRunner.query(`DROP TABLE IF EXISTS "business_members"`);
  }
}
