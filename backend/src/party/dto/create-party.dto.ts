import {
  IsBoolean,
  IsIn,
  IsInt,
  IsNotEmpty,
  IsNumber,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  Max,
  MaxLength,
  Min,
  ValidateIf,
} from 'class-validator';

export class CreatePartyDto {
  /**
   * Optional client-generated primary key. The Android app is offline-first and
   * mints a UUID locally so child ledger entries can reference the party before
   * it has ever reached the server. Sending it here makes create idempotent:
   * replaying the same id returns the existing row instead of inserting a
   * duplicate. Omit it and the server generates one as before.
   */
  @IsOptional()
  @IsUUID()
  id?: string;

  /**
   * @IsNotEmpty alone rejects only '' / null / undefined, so a whitespace-only
   * name ("   ") passed validation. That was survivable while the value was
   * stored verbatim, but PartyService now trims it as part of canonicalisation,
   * which would turn it into a genuinely empty name in the database. Requiring
   * one non-whitespace character keeps that from being reachable. Names with
   * surrounding spaces are unaffected — they still validate and are trimmed.
   */
  @IsString()
  @IsNotEmpty()
  @Matches(/\S/, { message: 'name must not be blank' })
  @MaxLength(120)
  name: string;

  /**
   * Optional. A party is identified by name; a phone number is contact detail,
   * not identity, and a shopkeeper frequently records a customer before ever
   * having one.
   *
   * Blank is accepted and stored blank. It is never replaced with a
   * placeholder: a fabricated number is indistinguishable from a real one to
   * every downstream consumer (SMS, reminders, the dialer), so the absence has
   * to survive as an absence. When a value *is* supplied it must still look
   * like a phone number — relaxing "required" must not also relax "valid".
   */
  @IsOptional()
  @ValidateIf((_dto, value) => value !== undefined && value !== null && value !== '')
  @IsString()
  @Matches(/^[+0-9][0-9\s\-()]{5,19}$/, {
    message: 'phone must be a valid phone number',
  })
  phone?: string;

  @IsIn(['CUSTOMER', 'SUPPLIER'])
  type: 'CUSTOMER' | 'SUPPLIER';

  @IsOptional()
  @IsString()
  @MaxLength(15)
  gstin?: string;

  /**
   * Accepted for backwards compatibility with the Android client, which sends a
   * locally-invented id (e.g. "biz_1"). The server ignores this value and derives
   * the real business from the JWT / x-business-id header instead.
   */
  @IsOptional()
  @IsString()
  businessId?: string;

  // Address fields are accepted and validated, but PartyService does not persist
  // them yet — the Party entity only has a single `address` column.
  @IsOptional()
  @IsString()
  @MaxLength(200)
  flatBuilding?: string;

  @IsOptional()
  @IsString()
  @MaxLength(200)
  areaLocality?: string;

  @IsOptional()
  @IsString()
  @Matches(/^[0-9]{6}$/, { message: 'pincode must be 6 digits' })
  pincode?: string;

  @IsOptional()
  @IsString()
  @MaxLength(100)
  city?: string;

  @IsOptional()
  @IsString()
  @MaxLength(100)
  state?: string;

  @IsOptional()
  @IsBoolean()
  isShippingSame?: boolean;

  /**
   * All optional and independent of any predefined period — a rate applies
   * exactly to whatever interestPeriodValue/interestPeriodUnit says, never
   * annualized or converted. rate/period are only required to be meaningful
   * (>0) when interestEnabled is explicitly true, so a client that omits
   * interest entirely (every existing app build) is unaffected.
   */
  @IsOptional()
  @IsBoolean()
  interestEnabled?: boolean;

  @ValidateIf((dto) => dto.interestEnabled === true)
  @IsNumber()
  @Min(0)
  @Max(1000)
  interestRate?: number;

  @ValidateIf((dto) => dto.interestEnabled === true)
  @IsInt()
  @Min(1)
  interestPeriodValue?: number;

  @ValidateIf((dto) => dto.interestEnabled === true)
  @IsIn(['DAYS', 'MONTHS'])
  interestPeriodUnit?: 'DAYS' | 'MONTHS';
}
