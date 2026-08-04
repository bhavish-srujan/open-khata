import { Injectable, UnauthorizedException, Logger, OnModuleInit } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from '../users/user.entity';
import { initializeApp, cert, getApps } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { ConfigService } from '@nestjs/config';

@Injectable()
export class AuthService implements OnModuleInit {
  private readonly logger = new Logger(AuthService.name);

  constructor(
    @InjectRepository(User)
    private userRepository: Repository<User>,
    private jwtService: JwtService,
    private configService: ConfigService,
  ) {}

  onModuleInit() {
    const projectId = this.configService.get<string>('FIREBASE_PROJECT_ID');
    const clientEmail = this.configService.get<string>('FIREBASE_CLIENT_EMAIL');
    const privateKey = this.configService.get<string>('FIREBASE_PRIVATE_KEY')?.replace(/\\n/g, '\n');

    if (projectId && clientEmail && privateKey) {
      if (getApps().length === 0) {
        initializeApp({
          credential: cert({
            projectId,
            clientEmail,
            privateKey,
          }),
        });
        this.logger.log('Firebase Admin SDK initialized successfully.');
      }
    } else {
      this.logger.warn('Firebase Admin credentials not found in environment. Auth verification will fail in production.');
    }
  }

  async verifyFirebaseToken(idToken: string): Promise<{ access_token: string, is_new_user: boolean }> {
    try {
      let phoneNumber = '';
      
      // Bypass firebase verification if we pass a special dev token (useful for local development without connecting to firebase)
      if (idToken.startsWith('DEV_TOKEN_')) {
        phoneNumber = idToken.replace('DEV_TOKEN_', '');
        this.logger.warn(`Using DEV_TOKEN for phone: ${phoneNumber}`);
      } else {
        const decodedToken = await getAuth().verifyIdToken(idToken);
        phoneNumber = decodedToken.phone_number || '';
        if (!phoneNumber) {
          throw new UnauthorizedException('Phone number not found in Firebase token');
        }
      }

      // Find or create user
      let user = await this.userRepository.findOneBy({ phone_number: phoneNumber });
      let is_new_user = false;

      if (!user) {
        user = this.userRepository.create({ phone_number: phoneNumber });
        await this.userRepository.save(user);
        is_new_user = true;
      }

      // Generate JWT
      const payload = { sub: user.id, phone: user.phone_number };
      return {
        access_token: await this.jwtService.signAsync(payload),
        is_new_user,
      };
    } catch (error) {
      this.logger.error('Error verifying Firebase token', error.message);
      throw new UnauthorizedException('Invalid Firebase ID Token');
    }
  }
}
