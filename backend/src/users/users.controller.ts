import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  ParseUUIDPipe,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import { UsersService } from './users.service';
import { AddStaffDto } from './dto/add-staff.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from './user.entity';

/**
 * Staff of the caller's business.
 *
 * Every route is scoped to the businessId on the verified token. There is no
 * parameter for choosing a business, so a caller cannot address one they are
 * not a member of.
 */
@Controller('users')
@UseGuards(JwtAuthGuard, RolesGuard)
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get('staff')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  getStaff(@Req() req) {
    return this.usersService.getStaff(req.user.businessId);
  }

  @Post('staff')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  addStaff(@Req() req, @Body() body: AddStaffDto) {
    return this.usersService.addStaff(
      req.user.businessId,
      body.phoneNumber,
      body.name,
      body.role,
    );
  }

  /** Takes the membership id, not the user id: the account itself is untouched. */
  @Delete('staff/:membershipId')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  @HttpCode(HttpStatus.NO_CONTENT)
  removeStaff(
    @Req() req,
    @Param('membershipId', new ParseUUIDPipe()) membershipId: string,
  ) {
    return this.usersService.removeStaff(
      req.user.businessId,
      membershipId,
      req.user.sub,
    );
  }
}
