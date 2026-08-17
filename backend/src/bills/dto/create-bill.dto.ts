import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayNotEmpty,
  IsArray,
  IsBoolean,
  IsNotEmpty,
  IsNumber,
  IsOptional,
  IsPositive,
  IsString,
  IsUUID,
  Max,
  MaxLength,
  Min,
  ValidateNested,
} from 'class-validator';

/** Largest value representable by bills.totalAmount, which is numeric(10,2). */
const MAX_BILL_TOTAL = 99_999_999.99;

/**
 * One line on a bill.
 *
 * The entity stores these as jsonb, so nothing at the database layer constrains
 * their shape. Validating here is the only thing between a client and arbitrary
 * JSON in the column.
 */
export class BillItemDto {
  @IsString()
  @IsNotEmpty()
  @MaxLength(200)
  description: string;

  @IsNumber({ maxDecimalPlaces: 3 })
  @IsPositive()
  @Max(1_000_000)
  quantity: number;

  @IsNumber({ maxDecimalPlaces: 2 })
  @Min(0)
  @Max(MAX_BILL_TOTAL)
  price: number;

  /** Percentage, not an absolute amount. */
  @IsOptional()
  @IsNumber({ maxDecimalPlaces: 2 })
  @Min(0)
  @Max(100)
  tax?: number;
}

/**
 * Bill creation body.
 *
 * This class previously carried no class-validator metadata at all. Under the
 * global pipe's whitelist that makes every property non-whitelisted, so
 * forbidNonWhitelisted rejected every request: bill creation returned 400 even
 * for valid input, and was impossible.
 *
 * businessId, ledgerEntryId and receiptUrl are deliberately absent — the first
 * comes from the JWT, and the other two are set by server-side flows rather
 * than by the client.
 */
export class CreateBillDto {
  /**
   * Optional client-generated primary key, following the same pattern as
   * CreatePartyDto/CreateLedgerEntryDto: makes create idempotent so a client
   * retry after a network timeout (or lost response) returns the original
   * bill instead of inserting a duplicate — which would otherwise silently
   * inflate the Sales report, since it sums Bill.totalAmount directly.
   */
  @IsOptional()
  @IsUUID()
  id?: string;

  @IsUUID()
  partyId: string;

  @IsNumber({ maxDecimalPlaces: 2 })
  @IsPositive()
  @Max(MAX_BILL_TOTAL)
  totalAmount: number;

  @IsArray()
  @ArrayNotEmpty()
  @ArrayMaxSize(200)
  @ValidateNested({ each: true })
  @Type(() => BillItemDto)
  items: BillItemDto[];

  @IsOptional()
  @IsBoolean()
  isGst?: boolean;
}
