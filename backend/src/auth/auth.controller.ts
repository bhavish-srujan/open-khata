import { Controller, Post, Body, HttpCode, HttpStatus } from '@nestjs/common';
import { AuthService } from './auth.service';

@Controller('auth')
export class AuthController {
  constructor(private authService: AuthService) {}

  @HttpCode(HttpStatus.OK)
  @Post('verify-token')
  verifyToken(@Body() body: { idToken: string }) {
    return this.authService.verifyFirebaseToken(body.idToken);
  }
}
