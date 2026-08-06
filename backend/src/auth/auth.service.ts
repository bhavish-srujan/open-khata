import { Injectable, UnauthorizedException, Logger, BadRequestException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from '../users/user.entity';
import { ConfigService } from '@nestjs/config';
import { HttpService } from '@nestjs/axios';
import { firstValueFrom } from 'rxjs';

import { Business } from '../business/business.entity';

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);
  private readonly smsGatewayUrl: string;
  private readonly smsGatewayUser: string;
  private readonly smsGatewayPass: string;

  constructor(
    @InjectRepository(User)
    private userRepository: Repository<User>,
    @InjectRepository(Business)
    private businessRepository: Repository<Business>,
    private jwtService: JwtService,
    private configService: ConfigService,
    private httpService: HttpService,
  ) {
    this.smsGatewayUrl = this.configService.get<string>('SMS_GATEWAY_URL') || '';
    this.smsGatewayUser = this.configService.get<string>('SMS_GATEWAY_USERNAME') || '';
    this.smsGatewayPass = this.configService.get<string>('SMS_GATEWAY_PASSWORD') || '';
  }

  private normalizeForGateway(value: string): string {
    const digits = this.normalizePhoneForStorage(value);
    if (!digits) return value;
    return `+91${digits}`;
  }

  private normalizePhoneForStorage(value: string): string {
    const digits = (value || '').replace(/\D/g, '');
    if (!digits) return '';
    if (digits.length === 10) return digits;
    if (digits.length === 11 && digits.startsWith('0')) return digits.substring(1);
    if (digits.length === 12 && digits.startsWith('91')) return digits.substring(2);
    if (digits.length === 13 && digits.startsWith('091')) return digits.substring(3);
    return digits;
  }

  private buildSmsGatewayUrl(): string {
    const url = this.smsGatewayUrl.trim();
    if (!url) return url;
    try {
      const parsed = new URL(url);
      if (parsed.pathname === '' || parsed.pathname === '/') {
        parsed.pathname = '/messages';
      } else if (!parsed.pathname.endsWith('/messages')) {
        parsed.pathname = parsed.pathname.replace(/\/$/, '') + '/messages';
      }
      return parsed.toString();
    } catch {
      return url.replace(/\/$/, '') + '/messages';
    }
  }

  // Normalize and lookup user by phone number to handle different device formats
  public async findUserByPhone(phoneNumber: string): Promise<User | null> {
    if (!phoneNumber) return null;
    const normalized = this.normalizePhoneForStorage(phoneNumber);
    if (!normalized) return null;

    const byNormalized = await this.userRepository.findOneBy({ normalized_phone: normalized });
    if (byNormalized) return byNormalized;

    const byRaw = await this.userRepository.findOneBy({ phone_number: phoneNumber });
    if (byRaw) {
      if (byRaw.normalized_phone !== normalized) {
        byRaw.normalized_phone = normalized;
        await this.userRepository.save(byRaw);
      }
      return byRaw;
    }

    // Fallback: match against any stored phone_number after stripping non-digit characters.
    // This handles legacy values like '+91 63666 05032', '0 6366605032', or formatted input.
    const byDigitsRaw = await this.userRepository.createQueryBuilder('user')
      .where('regexp_replace(user.phone_number, \'\\D\', \'\', \'g\') = :normalized', { normalized })
      .getOne();

    if (byDigitsRaw) {
      if (byDigitsRaw.normalized_phone !== normalized) {
        byDigitsRaw.normalized_phone = normalized;
        await this.userRepository.save(byDigitsRaw);
      }
      return byDigitsRaw;
    }

    return null;
  }

  async requestOtp(phoneNumber: string): Promise<{ success: boolean, message: string }> {
    if (!phoneNumber) {
      throw new BadRequestException('Phone number is required');
    }

    // Generate a 6-digit OTP
    const generatedOtp = Math.floor(100000 + Math.random() * 900000).toString();
    const expiry = new Date(Date.now() + 10 * 60 * 1000); // 10 minutes from now

    // Find or create user to store the OTP (try normalized lookup to handle formatting differences)
    let user = await this.findUserByPhone(phoneNumber);
    if (!user) {
      user = this.userRepository.create({ phone_number: phoneNumber, normalized_phone: this.normalizePhoneForStorage(phoneNumber) });
    }
    user.currentOtp = generatedOtp;
    user.otpExpiry = expiry;
    await this.userRepository.save(user);

    if (!this.smsGatewayUrl) {
      this.logger.warn(`MOCK SMS: Requested OTP for ${phoneNumber}. Use ${generatedOtp} to verify.`);
      return { success: true, message: 'OTP Sent successfully (Mock)' };
    }

    try {
      const targetUrl = this.buildSmsGatewayUrl();
      const payload = {
        phoneNumbers: [this.normalizeForGateway(phoneNumber)],
        message: `Your Khatabook verification code is: ${generatedOtp}. Valid for 10 minutes.`,
      };

      const timeoutMs = Number(this.configService.get<number>('SMS_GATEWAY_TIMEOUT_MS') || 5000);

      this.logger.debug(`Sending OTP to SMS gateway URL: ${targetUrl}`);
      await firstValueFrom(
        this.httpService.post(targetUrl, payload, {
          headers: { 'Content-Type': 'application/json' },
          timeout: timeoutMs,
          auth: {
            username: this.smsGatewayUser || '',
            password: this.smsGatewayPass || '',
          },
        }),
      );

      return { success: true, message: 'OTP Sent successfully' };
    } catch (error) {
      const responseData = error?.response?.data || error?.message || 'unknown error';
      this.logger.error('Error requesting OTP via Android SMS Gateway', responseData);
      throw new BadRequestException('Failed to send OTP');
    }
  }

  async verifyOtp(phoneNumber: string, otp: string): Promise<{ access_token: string, is_new_user: boolean }> {
    if (!phoneNumber || !otp) {
      throw new BadRequestException('Phone number and OTP are required');
    }

    const user = await this.findUserByPhone(phoneNumber);
    
    if (!user || !user.currentOtp) {
       throw new UnauthorizedException('No OTP requested for this number');
    }

    if (user.currentOtp !== otp) {
       throw new UnauthorizedException('Invalid OTP');
    }

    if (user.otpExpiry && new Date() > user.otpExpiry) {
       throw new UnauthorizedException('OTP has expired');
    }

    // OTP matched, clear it and increment session version
    user.currentOtp = null;
    user.otpExpiry = null;
    user.sessionVersion = (user.sessionVersion || 1) + 1;
    // Ensure normalized_phone is persisted using the same storage normalization logic.
    user.normalized_phone = this.normalizePhoneForStorage(user.phone_number);
    await this.userRepository.save(user);

    const is_new_user = !user.name; // Simple heuristic for new user

    // Find or create default business
    let business = await this.businessRepository.findOneBy({ ownerId: user.id });
    if (!business) {
      business = this.businessRepository.create({
        ownerId: user.id,
        name: 'My Khata Business',
        type: 'General Store'
      });
      await this.businessRepository.save(business);
    }

    // Generate JWT
    const payload = { 
      sub: user.id, 
      phone: user.phone_number, 
      businessId: business.id, 
      role: user.role,
      sessionVersion: user.sessionVersion
    };
    return {
      access_token: await this.jwtService.signAsync(payload),
      is_new_user,
    };
  }

  async logout(userId: string): Promise<{ success: boolean }> {
    const user = await this.userRepository.findOneBy({ id: userId });
    if (!user) return { success: true };
    user.sessionVersion = (user.sessionVersion || 1) + 1;
    await this.userRepository.save(user);
    return { success: true };
  }
}
