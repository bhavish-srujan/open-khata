import { IsOptional, IsString, IsNotEmpty, MaxLength } from 'class-validator';

/**
 * Business create/update bodies.
 *
 * Update was previously declared as `@Body() body: any` and applied with
 * Object.assign, so a request could set ownerId or id and hand the row to
 * another user. Only the three genuinely editable fields are accepted here, and
 * the global pipe's forbidNonWhitelisted rejects anything else outright.
 */

export class CreateBusinessDto {
  @IsString()
  @IsNotEmpty()
  @MaxLength(120)
  name: string;

  @IsOptional()
  @IsString()
  @MaxLength(60)
  type?: string;

  @IsOptional()
  @IsString()
  @MaxLength(20)
  gstNumber?: string;
}

export class UpdateBusinessDto {
  @IsOptional()
  @IsString()
  @IsNotEmpty()
  @MaxLength(120)
  name?: string;

  @IsOptional()
  @IsString()
  @MaxLength(60)
  type?: string;

  @IsOptional()
  @IsString()
  @MaxLength(20)
  gstNumber?: string;
}
