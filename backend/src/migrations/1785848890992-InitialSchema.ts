import { MigrationInterface, QueryRunner } from "typeorm";

export class InitialSchema1785848890992 implements MigrationInterface {
    name = 'InitialSchema1785848890992'

    public async up(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`CREATE TABLE "users" ("id" uuid NOT NULL DEFAULT uuid_generate_v4(), "phone_number" character varying NOT NULL, "name" character varying, "created_at" TIMESTAMP NOT NULL DEFAULT now(), "updated_at" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "UQ_17d1817f241f10a3dbafb169fd2" UNIQUE ("phone_number"), CONSTRAINT "PK_a3ffb1c0c8416b9fc6f907b7433" PRIMARY KEY ("id"))`);
        await queryRunner.query(`CREATE TABLE "businesses" ("id" uuid NOT NULL DEFAULT uuid_generate_v4(), "owner_id" uuid NOT NULL, "name" character varying NOT NULL, "gst_number" character varying, "type" character varying, "created_at" TIMESTAMP NOT NULL DEFAULT now(), "updated_at" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "PK_bc1bf63498dd2368ce3dc8686e8" PRIMARY KEY ("id"))`);
        await queryRunner.query(`CREATE TABLE "parties" ("id" uuid NOT NULL DEFAULT uuid_generate_v4(), "name" character varying NOT NULL, "phone" character varying NOT NULL, "type" character varying NOT NULL DEFAULT 'CUSTOMER', "gstin" character varying, "currentBalance" numeric(14,2) NOT NULL DEFAULT '0', "business_id" uuid NOT NULL, "address" character varying, "createdAt" TIMESTAMP NOT NULL DEFAULT now(), "updatedAt" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "PK_da698299dca60d55f0050dde935" PRIMARY KEY ("id"))`);
        await queryRunner.query(`CREATE TABLE "ledger_entries" ("id" uuid NOT NULL DEFAULT uuid_generate_v4(), "business_id" uuid NOT NULL, "party_id" uuid NOT NULL, "amount" numeric(14,2) NOT NULL, "type" character varying NOT NULL, "note" character varying DEFAULT '', "paymentMethod" character varying NOT NULL DEFAULT 'Cash', "source" character varying NOT NULL DEFAULT 'MANUAL', "status" character varying NOT NULL DEFAULT 'CONFIRMED', "createdAt" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "PK_6efcb84411d3f08b08450ae75d5" PRIMARY KEY ("id"))`);
        await queryRunner.query(`CREATE TABLE "balance_history" ("id" uuid NOT NULL DEFAULT uuid_generate_v4(), "business_id" uuid NOT NULL, "party_id" uuid NOT NULL, "ledger_entry_id" uuid, "previousBalance" numeric(14,2) NOT NULL, "newBalance" numeric(14,2) NOT NULL, "createdAt" TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT "PK_dc0b0a31a6896d2e4fd3f08042c" PRIMARY KEY ("id"))`);
        await queryRunner.query(`ALTER TABLE "businesses" ADD CONSTRAINT "FK_8881b96819252080592fe1592ea" FOREIGN KEY ("owner_id") REFERENCES "users"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
        await queryRunner.query(`ALTER TABLE "parties" ADD CONSTRAINT "FK_2b13a30bbeed63f3409f1db6d7d" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
        await queryRunner.query(`ALTER TABLE "ledger_entries" ADD CONSTRAINT "FK_ca5959f814bd6a29487adce9cba" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
        await queryRunner.query(`ALTER TABLE "ledger_entries" ADD CONSTRAINT "FK_239dbb35ab9a1d31c69cb3ec3d2" FOREIGN KEY ("party_id") REFERENCES "parties"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
        await queryRunner.query(`ALTER TABLE "balance_history" ADD CONSTRAINT "FK_5fedca33ddeec1f2c7526f9c5da" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
        await queryRunner.query(`ALTER TABLE "balance_history" ADD CONSTRAINT "FK_9e4b0f423aff60304956598cd65" FOREIGN KEY ("party_id") REFERENCES "parties"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
        await queryRunner.query(`ALTER TABLE "balance_history" ADD CONSTRAINT "FK_6e526f7e85a9eb464afeded6eb5" FOREIGN KEY ("ledger_entry_id") REFERENCES "ledger_entries"("id") ON DELETE NO ACTION ON UPDATE NO ACTION`);
    }

    public async down(queryRunner: QueryRunner): Promise<void> {
        await queryRunner.query(`ALTER TABLE "balance_history" DROP CONSTRAINT "FK_6e526f7e85a9eb464afeded6eb5"`);
        await queryRunner.query(`ALTER TABLE "balance_history" DROP CONSTRAINT "FK_9e4b0f423aff60304956598cd65"`);
        await queryRunner.query(`ALTER TABLE "balance_history" DROP CONSTRAINT "FK_5fedca33ddeec1f2c7526f9c5da"`);
        await queryRunner.query(`ALTER TABLE "ledger_entries" DROP CONSTRAINT "FK_239dbb35ab9a1d31c69cb3ec3d2"`);
        await queryRunner.query(`ALTER TABLE "ledger_entries" DROP CONSTRAINT "FK_ca5959f814bd6a29487adce9cba"`);
        await queryRunner.query(`ALTER TABLE "parties" DROP CONSTRAINT "FK_2b13a30bbeed63f3409f1db6d7d"`);
        await queryRunner.query(`ALTER TABLE "businesses" DROP CONSTRAINT "FK_8881b96819252080592fe1592ea"`);
        await queryRunner.query(`DROP TABLE "balance_history"`);
        await queryRunner.query(`DROP TABLE "ledger_entries"`);
        await queryRunner.query(`DROP TABLE "parties"`);
        await queryRunner.query(`DROP TABLE "businesses"`);
        await queryRunner.query(`DROP TABLE "users"`);
    }

}
