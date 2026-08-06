import {
  IsIn,
  IsNumber,
  IsOptional,
  IsPositive,
  IsString,
  IsUUID,
  Max,
  MaxLength,
} from 'class-validator';

/** Largest value representable by the ledger's numeric(14,2) amount column. */
const MAX_AMOUNT = 999_999_999_999.99;

export class CreateLedgerEntryDto {
  @IsUUID()
  partyId: string;

  @IsNumber({ maxDecimalPlaces: 2 })
  @IsPositive()
  @Max(MAX_AMOUNT)
  amount: number;

  @IsIn(['GAVE', 'GOT'])
  type: 'GAVE' | 'GOT';

  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @IsOptional()
  @IsString()
  @MaxLength(50)
  paymentMethod?: string;

  /**
   * Declared explicitly so the global ValidationPipe's `whitelist` does not strip
   * it — LedgerService persists this to distinguish OCR-scanned entries from
   * manually entered ones.
   */
  @IsOptional()
  @IsIn(['MANUAL', 'OCR'])
  source?: 'MANUAL' | 'OCR';
}
