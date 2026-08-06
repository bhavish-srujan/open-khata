import { DataSource } from 'typeorm';
import { config } from 'dotenv';
import { User } from './users/user.entity';
import { Party } from './party/party.entity';
import { LedgerEntry } from './ledger/ledger-entry.entity';
import { Business } from './business/business.entity';
import { BalanceHistory } from './ledger/balance-history.entity';

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
  entities: [User, Party, LedgerEntry, Business, BalanceHistory],
  migrations: ['src/migrations/*.ts'],
});
