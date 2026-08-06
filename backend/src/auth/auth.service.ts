import {
  Injectable,
  UnauthorizedException,
  Logger,
  BadRequestException,
  HttpException,
  HttpStatus,
} from '@nestjs/common';
import { randomInt, timingSafeEqual } from 'node:crypto';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from '../users/user.entity';
import { SmsService } from '../notifications/sms/sms.service';

import { Business } from '../business/business.entity';

/** How long an issued OTP stays valid. */
const OTP_TTL_MS = 10 * 60 * 1000;

/** Minimum gap between OTP issues for one number. Also stops the attempt
 *  counter being reset on demand. */
const OTP_RESEND_COOLDOWN_MS = 60 * 1000;

/** Wrong guesses before the code is burned and must be re-requested. */
const MAX_OTP_ATTEMPTS = 5;

/**
 * Constant-time comparison, so response timing cannot be used to learn how much
 * of a code was correct. Falls back to a plain false on length mismatch, which
 * leaks only the length — already known, since every OTP is six digits.
 */
function timingSafeEquals(a: string | null, b: string | null): boolean {
  if (!a || !b) return false;
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);

  constructor(
    @InjectRepository(User)
    private userRepository: Repository<User>,
    @InjectRepository(Business)
    private businessRepository: Repository<Business>,
    private jwtService: JwtService,
    private smsService: SmsService,
  ) {}

  private normalizePhoneForStorage(value: string): string {
    const digits = (value || '').replace(/\D/g, '');
    if (!digits) return '';
    if (digits.length === 10) return digits;
    if (digits.length === 11 && digits.startsWith('0')) return digits.substring(1);
    if (digits.length === 12 && digits.startsWith('91')) return digits.substring(2);
    if (digits.length === 13 && digits.startsWith('091')) return digits.substring(3);
    return digits;
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

  /** Invalidates the current code so a new one must be requested. */
  private async clearOtp(user: User): Promise<void> {
    user.currentOtp = null;
    user.otpExpiry = null;
    user.otpAttempts = 0;
    await this.userRepository.save(user);
  }

  async requestOtp(phoneNumber: string): Promise<{ success: boolean, message: string }> {
    if (!phoneNumber) {
      throw new BadRequestException('Phone number is required');
    }

    // Find or create user to store the OTP (try normalized lookup to handle formatting differences)
    let user = await this.findUserByPhone(phoneNumber);
    if (!user) {
      user = this.userRepository.create({ phone_number: phoneNumber, normalized_phone: this.normalizePhoneForStorage(phoneNumber) });
    }

    // Refuse a rapid re-issue. This is not only anti-SMS-bombing: without it,
    // an attacker who has burned the attempt budget could simply request a
    // fresh code to reset otpAttempts and keep guessing indefinitely.
    const issuedAt = user.otpExpiry
      ? new Date(user.otpExpiry.getTime() - OTP_TTL_MS)
      : null;
    if (
      user.currentOtp &&
      issuedAt &&
      Date.now() - issuedAt.getTime() < OTP_RESEND_COOLDOWN_MS
    ) {
      const waitSeconds = Math.ceil(
        (OTP_RESEND_COOLDOWN_MS - (Date.now() - issuedAt.getTime())) / 1000,
      );
      throw new HttpException(
        `Please wait ${waitSeconds}s before requesting another code.`,
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    // Cryptographically random, not Math.random: OTPs are a credential.
    const generatedOtp = randomInt(100000, 1000000).toString();
    const expiry = new Date(Date.now() + OTP_TTL_MS);

    user.currentOtp = generatedOtp;
    user.otpExpiry = expiry;
    user.otpAttempts = 0;
    await this.userRepository.save(user);

    if (!this.smsService.isConfigured()) {
      this.logger.warn(`MOCK SMS: Requested OTP for ${phoneNumber}. Use ${generatedOtp} to verify.`);
      return { success: true, message: 'OTP Sent successfully (Mock)' };
    }

    // Delegated to SmsService so the OTP payload cannot drift from the shape the
    // other senders use. The wire format is unchanged.
    const sent = await this.smsService.sendSms(
      phoneNumber,
      `Your Khatabook verification code is: ${generatedOtp}. Valid for 10 minutes.`,
    );

    if (!sent) {
      this.logger.error('Error requesting OTP via Android SMS Gateway');
      throw new BadRequestException('Failed to send OTP');
    }

    return { success: true, message: 'OTP Sent successfully' };
  }

  async verifyOtp(phoneNumber: string, otp: string): Promise<{ access_token: string, is_new_user: boolean }> {
    if (!phoneNumber || !otp) {
      throw new BadRequestException('Phone number and OTP are required');
    }

    const user = await this.findUserByPhone(phoneNumber);
    
    if (!user || !user.currentOtp) {
       throw new UnauthorizedException('No OTP requested for this number');
    }

    // Expiry is checked before the code itself, so an expired OTP cannot be
    // used to burn attempts, and the error is honest about why it failed.
    if (user.otpExpiry && new Date() > user.otpExpiry) {
       await this.clearOtp(user);
       throw new UnauthorizedException('OTP has expired');
    }

    if (!timingSafeEquals(user.currentOtp, otp)) {
       user.otpAttempts = (user.otpAttempts ?? 0) + 1;

       if (user.otpAttempts >= MAX_OTP_ATTEMPTS) {
         // Burn the code entirely. A fresh one must be requested, and the
         // resend cooldown bounds how fast that can happen.
         await this.clearOtp(user);
         this.logger.warn(
           `OTP locked out after ${MAX_OTP_ATTEMPTS} failed attempts for user ${user.id}`,
         );
         throw new UnauthorizedException(
           'Too many incorrect attempts. Please request a new code.',
         );
       }

       await this.userRepository.save(user);
       throw new UnauthorizedException('Invalid OTP');
    }

    // OTP matched, clear it and increment session version
    user.currentOtp = null;
    user.otpExpiry = null;
    user.otpAttempts = 0;
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
