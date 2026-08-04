import {
  Controller,
  Get,
  Post,
  Delete,
  Body,
  Param,
  Query,
  HttpCode,
  HttpStatus,
  UseGuards,
  Headers,
  BadRequestException,
} from '@nestjs/common';
import { PartyService } from './party.service';
import { CreatePartyDto } from './dto/create-party.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('api/v1/parties')
export class PartyController {
  constructor(private readonly partyService: PartyService) {}

  private extractBusinessId(headers: Record<string, string>): string {
    const businessId = headers['x-business-id'];
    if (!businessId) {
      throw new BadRequestException('x-business-id header is required');
    }
    return businessId as string;
  }

  @Get()
  getAllParties(@Headers() headers, @Query('type') type?: 'CUSTOMER' | 'SUPPLIER') {
    const businessId = this.extractBusinessId(headers);
    return this.partyService.findAll(businessId, type);
  }

  @Get(':id')
  getPartyById(@Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(headers);
    return this.partyService.findOne(id, businessId);
  }

  @Post()
  createParty(@Headers() headers, @Body() dto: CreatePartyDto) {
    const businessId = this.extractBusinessId(headers);
    return this.partyService.create(businessId, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteParty(@Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(headers);
    return this.partyService.delete(id, businessId);
  }
}
