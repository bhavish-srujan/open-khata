import { MigrationInterface, QueryRunner } from 'typeorm';

export class AddBusinessProfileFields1786557406269 implements MigrationInterface {
  name = 'AddBusinessProfileFields1786557406269';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "items" DROP CONSTRAINT "FK_items_business"`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" DROP CONSTRAINT "FK_bills_business"`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" DROP CONSTRAINT "FK_bills_party"`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" DROP CONSTRAINT "FK_bills_ledger_entry"`,
    );
    await queryRunner.query(
      `ALTER TABLE "receipts" DROP CONSTRAINT "FK_receipts_business"`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" DROP CONSTRAINT "FK_business_members_business"`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" DROP CONSTRAINT "FK_business_members_user"`,
    );
    await queryRunner.query(`DROP INDEX "public"."UQ_users_normalized_phone"`);
    await queryRunner.query(`DROP INDEX "public"."IDX_receipts_business"`);
    await queryRunner.query(`DROP INDEX "public"."IDX_business_members_user"`);
    await queryRunner.query(
      `ALTER TABLE "businesses" ADD "address" character varying`,
    );
    await queryRunner.query(
      `ALTER TABLE "businesses" ADD "category" character varying`,
    );
    await queryRunner.query(
      `ALTER TABLE "businesses" ADD "bank_account" character varying`,
    );
    await queryRunner.query(
      `ALTER TABLE "businesses" ADD "profile_image_url" character varying`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" ADD CONSTRAINT "UQ_4b8e2cef59fb50e431eb4acbb6f" UNIQUE ("normalized_phone")`,
    );
    await queryRunner.query(`ALTER TABLE "receipts" DROP COLUMN "uploaded_by"`);
    await queryRunner.query(
      `ALTER TABLE "receipts" ADD "uploaded_by" character varying NOT NULL`,
    );
    await queryRunner.query(
      `ALTER TABLE "items" ADD CONSTRAINT "FK_f062c728f49b350bb0f27e21bd8" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" ADD CONSTRAINT "FK_a4875e1f7432f89025c4dc0972a" FOREIGN KEY ("businessId") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" ADD CONSTRAINT "FK_67dde98253867913dc5e491aa52" FOREIGN KEY ("partyId") REFERENCES "parties"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" ADD CONSTRAINT "FK_71dbd56d39f1cf51fd2dacac3e1" FOREIGN KEY ("ledgerEntryId") REFERENCES "ledger_entries"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "receipts" ADD CONSTRAINT "FK_31399c88e987215a5b572312d00" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" ADD CONSTRAINT "FK_123f737f36b33e71fe0b3028fdc" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" ADD CONSTRAINT "FK_7a8eebc9f4792ffdc2d350643fe" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE NO ACTION`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "business_members" DROP CONSTRAINT "FK_7a8eebc9f4792ffdc2d350643fe"`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" DROP CONSTRAINT "FK_123f737f36b33e71fe0b3028fdc"`,
    );
    await queryRunner.query(
      `ALTER TABLE "receipts" DROP CONSTRAINT "FK_31399c88e987215a5b572312d00"`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" DROP CONSTRAINT "FK_71dbd56d39f1cf51fd2dacac3e1"`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" DROP CONSTRAINT "FK_67dde98253867913dc5e491aa52"`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" DROP CONSTRAINT "FK_a4875e1f7432f89025c4dc0972a"`,
    );
    await queryRunner.query(
      `ALTER TABLE "items" DROP CONSTRAINT "FK_f062c728f49b350bb0f27e21bd8"`,
    );
    await queryRunner.query(`ALTER TABLE "receipts" DROP COLUMN "uploaded_by"`);
    await queryRunner.query(
      `ALTER TABLE "receipts" ADD "uploaded_by" uuid NOT NULL`,
    );
    await queryRunner.query(
      `ALTER TABLE "users" DROP CONSTRAINT "UQ_4b8e2cef59fb50e431eb4acbb6f"`,
    );
    await queryRunner.query(
      `ALTER TABLE "businesses" DROP COLUMN "profile_image_url"`,
    );
    await queryRunner.query(
      `ALTER TABLE "businesses" DROP COLUMN "bank_account"`,
    );
    await queryRunner.query(`ALTER TABLE "businesses" DROP COLUMN "category"`);
    await queryRunner.query(`ALTER TABLE "businesses" DROP COLUMN "address"`);
    await queryRunner.query(
      `CREATE INDEX "IDX_business_members_user" ON "business_members" USING btree ("user_id") `,
    );
    await queryRunner.query(
      `CREATE INDEX "IDX_receipts_business" ON "receipts" USING btree ("business_id") `,
    );
    await queryRunner.query(
      `CREATE UNIQUE INDEX "UQ_users_normalized_phone" ON "users" USING btree ("normalized_phone") WHERE (normalized_phone IS NOT NULL)`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" ADD CONSTRAINT "FK_business_members_user" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "business_members" ADD CONSTRAINT "FK_business_members_business" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "receipts" ADD CONSTRAINT "FK_receipts_business" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" ADD CONSTRAINT "FK_bills_ledger_entry" FOREIGN KEY ("ledgerEntryId") REFERENCES "ledger_entries"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" ADD CONSTRAINT "FK_bills_party" FOREIGN KEY ("partyId") REFERENCES "parties"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "bills" ADD CONSTRAINT "FK_bills_business" FOREIGN KEY ("businessId") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
    await queryRunner.query(
      `ALTER TABLE "items" ADD CONSTRAINT "FK_items_business" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`,
    );
  }
}
