import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { APP_GUARD } from '@nestjs/core';
import { ScheduleModule } from '@nestjs/schedule';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { TypeOrmModule } from '@nestjs/typeorm';
import { buildDatabaseSsl } from './database-ssl';
import { PartyModule } from './party/party.module';
import { LedgerModule } from './ledger/ledger.module';
import { Business } from './business/business.entity';
import { BusinessModule } from './business/business.module';
import { BalanceHistory } from './ledger/balance-history.entity';
import { AuthModule } from './auth/auth.module';
import { User } from './users/user.entity';
import { Party } from './party/party.entity';
import { LedgerEntry } from './ledger/ledger-entry.entity';
import { RemindersModule } from './reminders/reminders.module';
import { ScanningModule } from './scanning/scanning.module';
import { UsersModule } from './users/users.module';
import { BillsModule } from './bills/bills.module';
import { InventoryModule } from './inventory/inventory.module';
import { ReportsModule } from './reports/reports.module';
import { AuditLogsModule } from './audit-logs/audit-logs.module';
import { HealthController } from './health/health.controller';
import { UploadsModule } from './uploads/uploads.module';
import { Item } from './inventory/item.entity';
import { AuditLog } from './audit-logs/audit-log.entity';
import { Bill } from './bills/entities/bill.entity';
import { Receipt } from './uploads/receipt.entity';
import { BusinessMember } from './business/business-member.entity';
import { NotificationsModule } from './notifications/notifications.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
    }),
    ScheduleModule.forRoot(),
    // Baseline per-IP limit for the whole API. The project previously had no
    // rate limiting of any kind, so OTP verification could be enumerated.
    // Auth routes tighten this further with their own @Throttle decorators.
    ThrottlerModule.forRoot([
      { name: 'default', ttl: 60_000, limit: 120 },
    ]),
    TypeOrmModule.forRootAsync({
      imports: [ConfigModule],
      inject: [ConfigService],
      useFactory: (configService: ConfigService) => {
        const databaseUrl = configService.get<string>('DATABASE_URL');
        if (!databaseUrl && configService.get<string>('NODE_ENV') === 'production') {
          throw new Error('DATABASE_URL is required in production. Set DATABASE_URL in your environment configuration.');
        }
        const url = databaseUrl || 'postgresql://postgres:password@localhost:5432/khatabook';
        return {
          type: 'postgres',
          url,
          // Verified TLS for any remote database; plain for local docker.
          ssl: buildDatabaseSsl(url),
          entities: [User, Party, LedgerEntry, Business, BalanceHistory, Item, AuditLog, Bill, Receipt, BusinessMember],
          synchronize: configService.get<string>('NODE_ENV') !== 'production', // Use migrations in production
        };
      },
    }),
    AuthModule,
    BusinessModule,
    PartyModule,
    LedgerModule,
    RemindersModule,
    ScanningModule,
    UsersModule,
    BillsModule,
    InventoryModule,
    ReportsModule,
    AuditLogsModule,
    UploadsModule,
    NotificationsModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: ThrottlerGuard }],
})
export class AppModule {}
