import { Controller, Get, Post, Body, Delete, Param, UseGuards } from '@nestjs/common';
import { UsersService } from './users.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from './user.entity';

@Controller('users')
@UseGuards(JwtAuthGuard, RolesGuard)
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get('staff')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  getStaff() {
    return this.usersService.getStaff();
  }

  @Post('staff')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  addStaff(@Body() body: { phoneNumber: string, name: string, role: UserRole }) {
    return this.usersService.addStaff(body.phoneNumber, body.name, body.role);
  }

  @Delete('staff/:id')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  removeStaff(@Param('id') id: string) {
    return this.usersService.removeStaff(id);
  }
}
