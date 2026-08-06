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
  /**
   * Optional client-generated primary key, sent by the offline-first Android
   * client. Makes create idempotent: replaying the same id returns the existing
   * entry *without* re-applying the balance delta, so a retried write can never
   * double-count money. Omit it and the server generates one as before.
   */
  @IsOptional()
  @IsUUID()
  id?: string;

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
