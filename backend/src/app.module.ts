import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AppController } from './app.controller';
import { AppService } from './app.service';
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

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
    }),
    TypeOrmModule.forRootAsync({
      imports: [ConfigModule],
      inject: [ConfigService],
      useFactory: (configService: ConfigService) => ({
        type: 'postgres',
        url: configService.get<string>('DATABASE_URL') || 'postgresql://postgres:password@localhost:5432/khatabook',
        entities: [User, Party, LedgerEntry, Business, BalanceHistory],
        synchronize: configService.get<string>('NODE_ENV') !== 'production', // Use migrations in production
      }),
    }),
    AuthModule,
    BusinessModule,
    PartyModule,
    LedgerModule,
    RemindersModule,
    ScanningModule,
  ],
  controllers: [AppController],
  providers: [AppService],
})
export class AppModule {}
