import { IsNotEmpty, IsString, Matches } from 'class-validator';

/**
 * Both auth bodies were previously declared as inline object literals, whose
 * runtime metatype is Object, so the global ValidationPipe skipped them
 * entirely and any shape was accepted.
 */

export class RequestOtpDto {
  @IsString()
  @IsNotEmpty()
  @Matches(/^[+0-9][0-9\s\-()]{5,19}$/, {
    message: 'phoneNumber must be a valid phone number',
  })
  phoneNumber: string;
}

export class VerifyOtpDto {
  @IsString()
  @IsNotEmpty()
  @Matches(/^[+0-9][0-9\s\-()]{5,19}$/, {
    message: 'phoneNumber must be a valid phone number',
  })
  phoneNumber: string;

  /** Exactly six digits — the only shape requestOtp ever issues. */
  @IsString()
  @Matches(/^[0-9]{6}$/, { message: 'otp must be 6 digits' })
  otp: string;
}
