import { Controller, Post, Body, HttpCode, HttpStatus, UseGuards, Request } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';
import { RequestOtpDto, VerifyOtpDto } from './dto/auth.dto';

// These two routes are unauthenticated and issue/consume a credential, so they
// carry far tighter per-IP limits than the API default. The per-user attempt
// counter in AuthService handles a targeted attack on one number; these limits
// handle volume and spraying across many numbers.
@Controller('auth')
export class AuthController {
  constructor(private authService: AuthService) {}

  @Throttle({ default: { ttl: 60_000, limit: 5 } })
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
