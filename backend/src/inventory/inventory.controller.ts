import { Controller, Get, Post, Body, Param, Put, Delete, UseGuards, Request } from '@nestjs/common';
import { InventoryService } from './inventory.service';
import { Item } from './item.entity';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from '../users/user.entity';

// JwtAuthGuard must run first: it populates req.user, which RolesGuard reads.
@Controller('inventory')
@UseGuards(JwtAuthGuard, RolesGuard)
export class InventoryController {
  constructor(private readonly inventoryService: InventoryService) {}

  @Get()
  @Roles(UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER)
  findAll(@Request() req) {
    const businessId = req.user.businessId;
    return this.inventoryService.findAll(businessId);
  }

  @Get('low-stock')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  getLowStockAlerts(@Request() req) {
    const businessId = req.user.businessId;
    return this.inventoryService.getLowStockAlerts(businessId);
  }

  @Get(':id')
  @Roles(UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER)
  findOne(@Param('id') id: string, @Request() req) {
    const businessId = req.user.businessId;
    return this.inventoryService.findOne(id, businessId);
  }

  @Post()
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  create(@Body() itemData: Partial<Item>, @Request() req) {
    const businessId = req.user.businessId;
    return this.inventoryService.create(itemData, businessId);
  }

  @Put(':id')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  update(@Param('id') id: string, @Body() itemData: Partial<Item>, @Request() req) {
    const businessId = req.user.businessId;
    return this.inventoryService.update(id, itemData, businessId);
  }

  @Delete(':id')
  @Roles(UserRole.OWNER)
  delete(@Param('id') id: string, @Request() req) {
    const businessId = req.user.businessId;
    return this.inventoryService.delete(id, businessId);
  }
}
