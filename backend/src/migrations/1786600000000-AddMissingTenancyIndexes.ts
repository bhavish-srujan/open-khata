import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Indexes the foreign keys every tenant-scoped query filters on.
 *
 * Postgres never auto-indexes a foreign key column (only the referenced side
 * needs one, for the constraint itself), and no prior migration ever added
 * one for parties.business_id, ledger_entries.business_id/party_id,
 * balance_history.*, items.business_id or bills.businessId/partyId. Every
 * one of PartyService.findAll, LedgerService.findByBusiness/findByParty,
 * InventoryService.findAll, BillsService.findAll and the cross-tenant
 * PartyService.delete cleanup filters on exactly these columns — at this
 * project's current size the sequential scan is invisible, but it is the
 * kind of thing that only gets noticed once a business has enough rows for
 * it to matter, which is too late to add an index for free.
 *
 * IDX_receipts_business and IDX_business_members_user are a separate case:
 * they existed before AddBusinessProfileFields1786557406269, which dropped
 * them as unintended collateral from a generated migration diff and never
 * recreated them. This restores exactly those two rather than re-deriving
 * them from scratch.
 */
export class AddMissingTenancyIndexes1786600000000 implements MigrationInterface {
  name = 'AddMissingTenancyIndexes1786600000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_parties_business" ON "parties" ("business_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_ledger_entries_business" ON "ledger_entries" ("business_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_ledger_entries_party" ON "ledger_entries" ("party_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_balance_history_business" ON "balance_history" ("business_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_balance_history_party" ON "balance_history" ("party_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_balance_history_ledger_entry" ON "balance_history" ("ledger_entry_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_items_business" ON "items" ("business_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_bills_business" ON "bills" ("businessId")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_bills_party" ON "bills" ("partyId")`,
    );
    // Restored — see class comment.
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_receipts_business" ON "receipts" ("business_id")`,
    );
    await queryRunner.query(
      `CREATE INDEX IF NOT EXISTS "IDX_business_members_user" ON "business_members" ("user_id")`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_business_members_user"`);
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_receipts_business"`);
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_bills_party"`);
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_bills_business"`);
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_items_business"`);
    await queryRunner.query(
      `DROP INDEX IF EXISTS "IDX_balance_history_ledger_entry"`,
    );
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_balance_history_party"`);
    await queryRunner.query(
      `DROP INDEX IF EXISTS "IDX_balance_history_business"`,
    );
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_ledger_entries_party"`);
    await queryRunner.query(
      `DROP INDEX IF EXISTS "IDX_ledger_entries_business"`,
    );
    await queryRunner.query(`DROP INDEX IF EXISTS "IDX_parties_business"`);
  }
}
