import { IsNumber, IsOptional, IsPositive, IsString, Max, MaxLength } from 'class-validator';

/** Largest value representable by the ledger's numeric(14,2) amount column. */
const MAX_AMOUNT = 999_999_999_999.99;

/**
 * Editable fields on an existing ledger entry.
 *
 * `type` and `partyId` are deliberately absent. Changing either would move
 * money between parties or flip the direction of a recorded transaction, which
 * is a delete-and-recreate, not an edit.
 */
export class UpdateLedgerEntryDto {
  @IsOptional()
  @IsNumber({ maxDecimalPlaces: 2 })
  @IsPositive()
  @Max(MAX_AMOUNT)
  amount?: number;

  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}
