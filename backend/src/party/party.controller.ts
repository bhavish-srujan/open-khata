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
  Req,
} from '@nestjs/common';
import { PartyService } from './party.service';
import { CreatePartyDto } from './dto/create-party.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@UseGuards(JwtAuthGuard)
@Controller('api/v1/parties')
export class PartyController {
  constructor(private readonly partyService: PartyService) {}

  /**
   * The business comes from the verified JWT only. It used to fall back to an
   * x-business-id header, which would have let a caller name any business they
   * liked had a token ever lacked the claim.
   */
  private extractBusinessId(req: any, _headers: Record<string, string>): string {
    const businessId = req.user?.businessId;

    if (!businessId) {
      throw new BadRequestException('Token does not carry a businessId');
    }
    return businessId as string;
  }

  @Get()
  getAllParties(@Req() req, @Headers() headers, @Query('type') type?: 'CUSTOMER' | 'SUPPLIER') {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.findAll(businessId, type);
  }

  @Get(':id')
  getPartyById(@Req() req, @Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.findOne(id, businessId);
  }

  @Post()
  createParty(@Req() req, @Headers() headers, @Body() dto: CreatePartyDto) {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.create(businessId, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteParty(@Req() req, @Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.delete(id, businessId);
  }
}

