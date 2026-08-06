import {
  IsBoolean,
  IsIn,
  IsNotEmpty,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  MaxLength,
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

  @IsString()
  @IsNotEmpty()
  @MaxLength(120)
  name: string;

  @IsString()
  @IsNotEmpty()
  @Matches(/^[+0-9][0-9\s\-()]{5,19}$/, {
    message: 'phone must be a valid phone number',
  })
  phone: string;

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
}
