import { Controller, Get, Post, Body, Param, UseGuards, Request } from '@nestjs/common';
import { BillsService } from './bills.service';
import { CreateBillDto } from './dto/create-bill.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@Controller('bills')
@UseGuards(JwtAuthGuard)
export class BillsController {
  constructor(private readonly billsService: BillsService) {}

  @Post()
  create(@Body() createBillDto: CreateBillDto, @Request() req) {
    return this.billsService.create(createBillDto, req.user.businessId);
  }

  @Get()
  findAll(@Request() req) {
    return this.billsService.findAll(req.user.businessId);
  }

  @Get(':id')
  findOne(@Param('id') id: string, @Request() req) {
    return this.billsService.findOne(id, req.user.businessId);
  }
}
