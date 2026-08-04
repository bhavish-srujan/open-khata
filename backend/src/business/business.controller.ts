import { Controller, Post, Get, Put, Delete, Body, Param, UseGuards, Request } from '@nestjs/common';
import { BusinessService } from './business.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('businesses')
export class BusinessController {
  constructor(private readonly businessService: BusinessService) {}

  @Post()
  create(@Request() req, @Body() body: { name: string; type?: string; gstNumber?: string }) {
    return this.businessService.create(req.user.sub, body.name, body.type, body.gstNumber);
  }

  @Get()
  findAll(@Request() req) {
    return this.businessService.findAllByOwner(req.user.sub);
  }

  @Get(':id')
  findOne(@Request() req, @Param('id') id: string) {
    return this.businessService.findOne(id, req.user.sub);
  }

  @Put(':id')
  update(@Request() req, @Param('id') id: string, @Body() body: any) {
    return this.businessService.update(id, req.user.sub, body);
  }

  @Delete(':id')
  delete(@Request() req, @Param('id') id: string) {
    return this.businessService.delete(id, req.user.sub);
  }
}
