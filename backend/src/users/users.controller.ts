import {
  Controller,
  Get,
  Post,
  Delete,
  NotImplementedException,
  UseGuards,
} from '@nestjs/common';
import { UsersService } from './users.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from './user.entity';

/**
 * Staff management is disabled.
 *
 * There is no association between a user and a business anywhere in the schema:
 * User carries a single global `role` column and there is no membership table.
 * getStaff() therefore returned every MANAGER and CASHIER in the database —
 * names and phone numbers belonging to other businesses — to any owner who
 * asked, and addStaff() created users attached to nothing.
 *
 * Nothing in the app calls these routes; AddStaffScreen makes no network
 * request. Rather than leave a cross-tenant leak reachable behind a valid
 * token, the routes answer 501 until a business_members table exists to scope
 * them. The service methods are left in place for that work to build on.
 */
@Controller('users')
@UseGuards(JwtAuthGuard, RolesGuard)
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  private notImplemented(): never {
    throw new NotImplementedException(
      'Staff management is unavailable: staff are not yet scoped to a business.',
    );
  }

  @Get('staff')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  getStaff() {
    this.notImplemented();
  }

  @Post('staff')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  addStaff() {
    this.notImplemented();
  }

  @Delete('staff/:id')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  removeStaff() {
    this.notImplemented();
  }
}
