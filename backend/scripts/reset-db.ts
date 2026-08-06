import { config } from 'dotenv';
import { AppDataSource } from '../src/data-source';
import fs from 'fs';
import path from 'path';

config({ path: path.resolve(__dirname, '../.env') });

const uploadsPath = path.resolve(__dirname, '../uploads');
const clearUploadsFlag = process.argv.includes('--all') || process.argv.includes('-a');

async function resetDatabase() {
  await AppDataSource.initialize();
  console.log('Connected to database.');

  const queryRunner = AppDataSource.createQueryRunner();
  await queryRunner.connect();

  try {
    console.log('Dropping public schema and recreating it...');
    await queryRunner.query('DROP SCHEMA public CASCADE');
    await queryRunner.query('CREATE SCHEMA public');
    await queryRunner.query('GRANT ALL ON SCHEMA public TO public');

    console.log('Ensuring uuid-ossp extension is available...');
    await queryRunner.query(`CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`);

    console.log('Recreating database schema from migrations...');
    await AppDataSource.runMigrations();
    console.log('Database reset complete.');
  } finally {
    await queryRunner.release();
    await AppDataSource.destroy();
  }
}

function clearUploads() {
  if (!fs.existsSync(uploadsPath)) {
    console.log('Uploads folder does not exist. Skipping upload cleanup.');
    return;
  }

  const items = fs.readdirSync(uploadsPath);
  for (const item of items) {
    const itemPath = path.join(uploadsPath, item);
    try {
      fs.rmSync(itemPath, { recursive: true, force: true });
      console.log(`Removed ${itemPath}`);
    } catch (error) {
      console.warn(`Failed to remove ${itemPath}:`, error instanceof Error ? error.message : error);
    }
  }
}

async function main() {
  console.log('Starting backend reset...');
  await resetDatabase();

  if (clearUploadsFlag) {
    console.log('Clearing uploaded receipts and files...');
    clearUploads();
  }

  console.log('Reset script finished.');
}

main().catch((error) => {
  console.error('Reset script failed:', error instanceof Error ? error.message : error);
  console.error('Error object:', error, 'type:', typeof error);
  try {
    const util = require('util');
    console.error('Error inspect:', util.inspect(error, { depth: 5, colors: false }));
  } catch {
    // ignore if util load fails
  }
  process.exit(1);
});
