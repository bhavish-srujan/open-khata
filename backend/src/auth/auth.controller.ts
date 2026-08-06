import { Controller, Post, Body, HttpCode, HttpStatus, UseGuards, Request } from '@nestjs/common';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';

@Controller('auth')
export class AuthController {
  constructor(private authService: AuthService) {}

  @HttpCode(HttpStatus.OK)
  @Post('request-otp')
  requestOtp(@Body() body: { phoneNumber: string }) {
    return this.authService.requestOtp(body.phoneNumber);
  }

  @HttpCode(HttpStatus.OK)
  @Post('verify-otp')
  verifyOtp(@Body() body: { phoneNumber: string, otp: string }) {
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
