import { DataSource } from 'typeorm';
import { config } from 'dotenv';
import { User } from './users/user.entity';
import { Party } from './party/party.entity';
import { LedgerEntry } from './ledger/ledger-entry.entity';
import { Business } from './business/business.entity';
import { BalanceHistory } from './ledger/balance-history.entity';
import { Item } from './inventory/item.entity';
import { AuditLog } from './audit-logs/audit-log.entity';
import { Bill } from './bills/entities/bill.entity';
import { Receipt } from './uploads/receipt.entity';

config(); // Load .env

const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) {
  throw new Error('DATABASE_URL is required. Set DATABASE_URL in your environment configuration.');
}

export const AppDataSource = new DataSource({
  type: 'postgres',
  url: databaseUrl,
  synchronize: false,
  logging: true,
  // Must match app.module.ts exactly, or migration:generate will emit DROP
  // statements for whichever entities are missing here.
  entities: [User, Party, LedgerEntry, Business, BalanceHistory, Item, AuditLog, Bill, Receipt],
  migrations: ['src/migrations/*.ts'],
});
