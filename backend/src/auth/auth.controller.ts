import {
  Controller,
  Post,
  Body,
  HttpCode,
  HttpStatus,
  UseGuards,
  Request,
} from '@nestjs/common';
import { SkipThrottle, Throttle } from '@nestjs/throttler';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';
import { RequestOtpDto, VerifyOtpDto } from './dto/auth.dto';

// verify-otp is unauthenticated and consumes a credential, so it carries a far
// tighter per-IP limit than the API default. The per-user attempt counter in
// AuthService handles a targeted attack on one number; this limit handles
// volume and spraying across many numbers.
@Controller('auth')
export class AuthController {
  constructor(private authService: AuthService) {}

  // Deliberately unthrottled: no per-IP limit and no per-user resend cooldown
  // (see AuthService.requestOtp) — a user can request a fresh code as often as
  // they want. verify-otp's throttle below and the per-user attempt lockout in
  // AuthService are what remain to defend the codes this issues.
  @SkipThrottle()
  @HttpCode(HttpStatus.OK)
  @Post('request-otp')
  requestOtp(@Body() body: RequestOtpDto) {
    return this.authService.requestOtp(body.phoneNumber);
  }

  @Throttle({ default: { ttl: 60_000, limit: 10 } })
  @HttpCode(HttpStatus.OK)
  @Post('verify-otp')
  verifyOtp(@Body() body: VerifyOtpDto) {
    return this.authService.verifyOtp(body.phoneNumber, body.otp);
  }

  @UseGuards(JwtAuthGuard)
  @HttpCode(HttpStatus.OK)
  @Post('logout')
  logout(@Request() req) {
    const userId = req.user?.sub;
    return this.authService.logout(userId);
  }
}
