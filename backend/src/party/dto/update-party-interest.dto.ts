import {
  IsBoolean,
  IsIn,
  IsInt,
  IsNumber,
  Max,
  Min,
  ValidateIf,
} from 'class-validator';

/**
 * The interest configuration on an existing party.
 *
 * Deliberately the only editable surface on this endpoint: name, phone, type
 * and balance are not accepted here. Changing a phone would silently redirect
 * a customer's SMS, and a balance is only ever moved by a ledger entry — both
 * are separate operations, not an interest edit.
 *
 * Same validation shape as CreatePartyDto's interest fields: rate/period are
 * only required to be meaningful when interestEnabled is true, so turning
 * interest off never requires sending values that are about to be ignored.
 */
export class UpdatePartyInterestDto {
  @IsBoolean()
  interestEnabled: boolean;

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
