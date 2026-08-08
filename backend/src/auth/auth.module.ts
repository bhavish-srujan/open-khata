import { Module, Global } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';
import { User } from '../users/user.entity';

import { Business } from '../business/business.entity';
import { BusinessMember } from '../business/business-member.entity';

@Global()
@Module({
  imports: [
    TypeOrmModule.forFeature([User, Business, BusinessMember]),
    JwtModule.registerAsync({
      global: true,
      imports: [ConfigModule],
      inject: [ConfigService],
      useFactory: async (configService: ConfigService) => {
        const secret = configService.get<string>('JWT_SECRET');
        if (!secret) {
          throw new Error('JWT_SECRET is required. Set JWT_SECRET in your environment configuration.');
        }
        // A short secret is brute-forceable offline from any single token, and
        // forging one then grants any identity in any business. 32 characters
        // is the floor for HS256.
        if (secret.trim().length < 32) {
          throw new Error(
            'JWT_SECRET must be at least 32 characters. Generate one with: openssl rand -base64 48',
          );
        }
        return {
          secret,
          signOptions: { expiresIn: '7d' },
        };
      },
    }),
  ],
  controllers: [AuthController],
  providers: [AuthService, JwtAuthGuard],
  exports: [AuthService, JwtAuthGuard, TypeOrmModule],
})
export class AuthModule {}
