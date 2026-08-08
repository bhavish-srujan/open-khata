import { IsIn, IsNotEmpty, IsString, Matches, MaxLength } from 'class-validator';
import { UserRole } from '../user.entity';

/**
 * Adding a member to the caller's business.
 *
 * businessId is deliberately absent — it comes from the JWT. OWNER is excluded
 * from the accepted roles at the type level as well as in the service, so a
 * request cannot even express an ownership grant.
 */
export class AddStaffDto {
  @IsString()
  @IsNotEmpty()
  @Matches(/^[+0-9][0-9\s\-()]{5,19}$/, {
    message: 'phoneNumber must be a valid phone number',
  })
  phoneNumber: string;

  @IsString()
  @IsNotEmpty()
  @MaxLength(120)
  name: string;

  @IsIn([UserRole.MANAGER, UserRole.CASHIER], {
    message: 'role must be MANAGER or CASHIER',
  })
  role: UserRole.MANAGER | UserRole.CASHIER;
}
