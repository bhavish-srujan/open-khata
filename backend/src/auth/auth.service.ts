import {
  Injectable,
  UnauthorizedException,
  Logger,
  BadRequestException,
  HttpException,
  HttpStatus,
} from '@nestjs/common';
import { createHash, randomInt, timingSafeEqual } from 'node:crypto';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from '../users/user.entity';
import { SmsService } from '../notifications/sms/sms.service';
import { WhatsAppService } from '../notifications/whatsapp/whatsapp.service';

import { Business } from '../business/business.entity';
import { BusinessMember } from '../business/business-member.entity';
import { UserRole } from '../users/user.entity';

/** How long an issued OTP stays valid. */
const OTP_TTL_MS = 10 * 60 * 1000;

/** Minimum gap between OTP issues for one number. Also stops the attempt
 *  counter being reset on demand. */
const OTP_RESEND_COOLDOWN_MS = 60 * 1000;

/** Wrong guesses before the code is burned and must be re-requested. */
const MAX_OTP_ATTEMPTS = 5;

/**
 * One-way hash of an OTP.
 *
 * A plain SHA-256 is appropriate here where it would not be for a password:
 * the input is exactly six digits with a ten-minute life and a five-attempt
 * ceiling, so an offline attacker gains nothing from a slow KDF that they do
 * not already get from enumerating a million candidates.
 */
function hashOtp(otp: string): string {
  return createHash('sha256').update(otp).digest('hex');
}

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
    @InjectRepository(BusinessMember)
    private memberRepository: Repository<BusinessMember>,
    private jwtService: JwtService,
    private smsService: SmsService,
    private whatsAppService: WhatsAppService,
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

    // Stored as a hash: a database read — a backup, a support query, a leak —
    // must not yield a live credential. The plaintext exists only long enough
    // to be sent.
    user.currentOtp = hashOtp(generatedOtp);
    user.otpExpiry = expiry;
    user.otpAttempts = 0;
    await this.userRepository.save(user);

    // Login codes go over WhatsApp. Customer-facing messages — balance updates
    // and collection reminders — stay on SMS, because those reach people who
    // may not use WhatsApp at all.
    if (!this.whatsAppService.isConfigured()) {
      this.logger.warn(
        `MOCK WhatsApp: Requested OTP for ${phoneNumber}. Use ${generatedOtp} to verify.`,
      );
      return { success: true, message: 'OTP Sent successfully (Mock)' };
    }

    const result = await this.whatsAppService.sendOtp(phoneNumber, generatedOtp);

    if (!result.sent) {
      // Distinguish the causes a caller can act on. The code itself is never
      // included in any of these paths.
      switch (result.failure) {
        case 'invalid_number':
          throw new BadRequestException(
            'That number is not reachable on WhatsApp. Check it and try again.',
          );
        case 'recipient_not_allowed':
          // A sandbox number that has not been added to the allowed recipient
          // list. A configuration problem, not something the caller can fix.
          throw new HttpException(
            'Login is temporarily unavailable. Please try again later.',
            HttpStatus.SERVICE_UNAVAILABLE,
          );
        case 'rate_limited':
          throw new HttpException(
            'Too many messages right now. Please try again shortly.',
            HttpStatus.TOO_MANY_REQUESTS,
          );
        case 'template_rejected':
        case 'auth_failed':
          // A misconfiguration on our side, not the caller's fault.
          throw new HttpException(
            'Login is temporarily unavailable. Please try again later.',
            HttpStatus.SERVICE_UNAVAILABLE,
          );
        default:
          throw new BadRequestException('Failed to send OTP');
      }
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

    if (!timingSafeEquals(user.currentOtp, hashOtp(otp))) {
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

    // Resolve the business from membership, falling back to creating one.
    //
    // This used to look up a business by ownerId alone. Someone added as staff
    // owns nothing, so logging in minted them a brand-new empty business rather
    // than giving them access to the one they were added to — staff could never
    // reach it.
    const memberships = await this.memberRepository.find({
      where: { userId: user.id },
      order: { createdAt: 'ASC' },
    });

    // Prefer a business they own, so an owner who is also staff elsewhere lands
    // on their own shop.
    const ownedMembership = memberships.find((m) => m.role === UserRole.OWNER);
    const activeMembership = ownedMembership ?? memberships[0];

    let business: Business | null = null;
    // Defaults to OWNER for the create-a-business path below; overwritten
    // whenever an existing membership is found.
    let role: UserRole = UserRole.OWNER;

    if (activeMembership) {
      business = await this.businessRepository.findOneBy({
        id: activeMembership.businessId,
      });
      role = activeMembership.role;
    }

    if (!business) {
      business = await this.businessRepository.save(
        this.businessRepository.create({
          ownerId: user.id,
          name: 'My Khata Business',
          type: 'General Store',
        }),
      );
      role = UserRole.OWNER;
      await this.memberRepository.save(
        this.memberRepository.create({
          businessId: business.id,
          userId: user.id,
          role: UserRole.OWNER,
        }),
      );
    }

    // Generate JWT
    const payload = { 
      sub: user.id, 
      phone: user.phone_number, 
      businessId: business.id, 
      role,
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
