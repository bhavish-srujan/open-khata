import { DataSource } from 'typeorm';
import { config } from 'dotenv';
import { User } from './users/user.entity';
import { Party } from './party/party.entity';
import { LedgerEntry } from './ledger/ledger-entry.entity';
import { Business } from './business/business.entity';
import { BalanceHistory } from './ledger/balance-history.entity';

config(); // Load .env

export const AppDataSource = new DataSource({
  type: 'postgres',
  url: process.env.DATABASE_URL || 'postgresql://postgres:password@localhost:5432/khatabook',
  synchronize: false,
  logging: true,
  entities: [User, Party, LedgerEntry, Business, BalanceHistory],
  migrations: ['src/migrations/*.ts'],
});
