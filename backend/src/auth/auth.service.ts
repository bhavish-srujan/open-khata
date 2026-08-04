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

  async requestOtp(phoneNumber: string): Promise<{ success: boolean, message: string }> {
    if (!phoneNumber) {
      throw new BadRequestException('Phone number is required');
    }

    // Generate a 6-digit OTP
    const generatedOtp = Math.floor(100000 + Math.random() * 900000).toString();
    const expiry = new Date(Date.now() + 10 * 60 * 1000); // 10 minutes from now

    // Find or create user to store the OTP
    let user = await this.userRepository.findOneBy({ phone_number: phoneNumber });
    if (!user) {
      user = this.userRepository.create({ phone_number: phoneNumber });
    }
    user.currentOtp = generatedOtp;
    user.otpExpiry = expiry;
    await this.userRepository.save(user);

    if (!this.smsGatewayUrl) {
      this.logger.warn(`MOCK SMS: Requested OTP for ${phoneNumber}. Use ${generatedOtp} to verify.`);
      return { success: true, message: 'OTP Sent successfully (Mock)' };
    }

    try {
      const payload = {
        textMessage: {
          text: `Your Khatabook verification code is: ${generatedOtp}. Valid for 10 minutes.`
        },
        phoneNumbers: [phoneNumber]
      };

      const authHeader = 'Basic ' + Buffer.from(`${this.smsGatewayUser}:${this.smsGatewayPass}`).toString('base64');
      
      await firstValueFrom(
        this.httpService.post(this.smsGatewayUrl, payload, { 
          headers: { 
            Authorization: authHeader,
            'Content-Type': 'application/json'
          } 
        })
      );

      return { success: true, message: 'OTP Sent successfully' };
    } catch (error) {
      this.logger.error('Error requesting OTP via Android SMS Gateway', error);
      throw new BadRequestException('Failed to send OTP');
    }
  }

  async verifyOtp(phoneNumber: string, otp: string): Promise<{ access_token: string, is_new_user: boolean }> {
    if (!phoneNumber || !otp) {
      throw new BadRequestException('Phone number and OTP are required');
    }

    const user = await this.userRepository.findOneBy({ phone_number: phoneNumber });
    
    if (!user || !user.currentOtp) {
       throw new UnauthorizedException('No OTP requested for this number');
    }

    if (user.currentOtp !== otp) {
       throw new UnauthorizedException('Invalid OTP');
    }

    if (user.otpExpiry && new Date() > user.otpExpiry) {
       throw new UnauthorizedException('OTP has expired');
    }

    // OTP matched, clear it
    user.currentOtp = null;
    user.otpExpiry = null;
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
    const payload = { sub: user.id, phone: user.phone_number, businessId: business.id };
    return {
      access_token: await this.jwtService.signAsync(payload),
      is_new_user,
    };
  }
}
