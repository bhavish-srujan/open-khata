import 'reflect-metadata';
import * as fs from 'fs';
import * as path from 'path';
import { getMetadataArgsStorage } from 'typeorm';
import { User } from './users/user.entity';
import { Party } from './party/party.entity';
import { LedgerEntry } from './ledger/ledger-entry.entity';
import { Business } from './business/business.entity';
import { BalanceHistory } from './ledger/balance-history.entity';
import { Item } from './inventory/item.entity';
import { AuditLog } from './audit-logs/audit-log.entity';
import { Bill } from './bills/entities/bill.entity';
import { Receipt } from './uploads/receipt.entity';
import { BusinessMember } from './business/business-member.entity';

/**
 * Guards the migration set against the entities.
 *
 * The app runs with `synchronize: true` outside production, so schema changes
 * were being applied implicitly and the migrations silently fell years behind:
 * three tables and five columns existed only by synchronisation. Production runs
 * migrations, so anything missing here simply does not exist there.
 *
 * This compares TypeORM's own entity metadata against the SQL in the migration
 * files, so forgetting a migration fails the build rather than production.
 */
const ENTITIES = [
  User,
  Party,
  LedgerEntry,
  Business,
  BalanceHistory,
  Item,
  AuditLog,
  Bill,
  Receipt,
  BusinessMember,
];

/** Table name and column names exactly as TypeORM will address them. */
function describeEntity(entity: Function) {
  const storage = getMetadataArgsStorage();
  const table = storage.tables.find((t) => t.target === entity);
  const columns = storage
    .filterColumns(entity)
    .map((c) => (c.options as any)?.name ?? c.propertyName);
  return { table: table?.name, columns };
}

/** Every quoted identifier the migrations mention for a given table. */
function migrationIdentifiersByTable(): Map<string, Set<string>> {
  // Deliberately lives outside src/migrations: data-source.ts globs that
  // directory as `src/migrations/*.ts`, so TypeORM would import this spec as a
  // migration and blow up on `describe` at CLI time.
  const dir = path.join(__dirname, 'migrations');
  const sql = fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.ts') && !f.endsWith('.spec.ts'))
    .map((f) => fs.readFileSync(path.join(dir, f), 'utf8'))
    .join('\n');

  const byTable = new Map<string, Set<string>>();
  const record = (table: string, body: string) => {
    const found = byTable.get(table) ?? new Set<string>();
    for (const [, ident] of body.matchAll(/"([a-zA-Z_][a-zA-Z0-9_]*)"/g)) {
      found.add(ident);
    }
    byTable.set(table, found);
  };

  // CREATE TABLE "x" ( ... )
  for (const [, table, body] of sql.matchAll(
    /CREATE TABLE (?:IF NOT EXISTS )?"([a-z_]+)" \(([\s\S]*?)\)`/g,
  )) {
    record(table, body);
  }

  // ALTER TABLE "x" ADD COLUMN [IF NOT EXISTS] "y" ...
  for (const [, table, rest] of sql.matchAll(
    /ALTER TABLE "([a-z_]+)" ADD COLUMN (?:IF NOT EXISTS )?([\s\S]*?)`/g,
  )) {
    record(table, rest);
  }

  return byTable;
}

describe('migrations cover the entities', () => {
  const migrationTables = migrationIdentifiersByTable();

  it.each(ENTITIES.map((e) => [e.name, e] as const))(
    '%s has a table in the migrations',
    (_name, entity) => {
      const { table } = describeEntity(entity);
      expect(table).toBeDefined();
      expect([...migrationTables.keys()]).toContain(table);
    },
  );

  it.each(ENTITIES.map((e) => [e.name, e] as const))(
    '%s has every column in the migrations',
    (_name, entity) => {
      const { table, columns } = describeEntity(entity);
      const covered = migrationTables.get(table!) ?? new Set<string>();

      const missing = columns.filter((c) => !covered.has(c));
      expect(missing).toEqual([]);
    },
  );
});

describe('data-source.ts and app.module.ts agree on entities', () => {
  const entityList = (file: string) => {
    const source = fs.readFileSync(path.join(__dirname, file), 'utf8');
    const match = source.match(/entities:\s*\[([^\]]*)\]/);
    return (match?.[1] ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .sort();
  };

  it('lists the same entities in both places', () => {
    // A mismatch makes migration:generate emit DROP TABLE for whichever
    // entities data-source.ts omits.
    expect(entityList('data-source.ts')).toEqual(entityList('app.module.ts'));
  });

  it('lists every entity this spec knows about', () => {
    expect(entityList('data-source.ts')).toEqual(
      ENTITIES.map((e) => e.name).sort(),
    );
  });
});
