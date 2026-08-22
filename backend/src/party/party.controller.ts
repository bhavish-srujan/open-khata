import {
  Controller,
  Get,
  Post,
  Patch,
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
import { UpdatePartyInterestDto } from './dto/update-party-interest.dto';
import { UpdateCollectionFrequencyDto } from './dto/update-collection-frequency.dto';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { Roles } from '../auth/roles.decorator';
import { UserRole } from '../users/user.entity';

// JwtAuthGuard must run first: it populates req.user, which RolesGuard reads.
@UseGuards(JwtAuthGuard, RolesGuard)
@Controller('api/v1/parties')
export class PartyController {
  constructor(private readonly partyService: PartyService) {}

  /**
   * The business comes from the verified JWT only. It used to fall back to an
   * x-business-id header, which would have let a caller name any business they
   * liked had a token ever lacked the claim.
   */
  private extractBusinessId(
    req: any,
    _headers: Record<string, string>,
  ): string {
    const businessId = req.user?.businessId;

    if (!businessId) {
      throw new BadRequestException('Token does not carry a businessId');
    }
    return businessId as string;
  }

  @Get()
  getAllParties(
    @Req() req,
    @Headers() headers,
    @Query('type') type?: 'CUSTOMER' | 'SUPPLIER',
  ) {
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

  /**
   * Interest configuration only — see UpdatePartyInterestDto for why nothing
   * else is editable here. Left open to every business member (unlike
   * delete): this changes no balance and destroys no record, it only sets the
   * rate a later, explicit interest charge would use.
   */
  @Patch(':id/interest')
  updatePartyInterest(
    @Req() req,
    @Headers() headers,
    @Param('id') id: string,
    @Body() dto: UpdatePartyInterestDto,
  ) {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.updateInterest(id, businessId, dto);
  }

  /**
   * The party's Daily/Weekly/Monthly bucket. Open to every business member for
   * the same reason as interest: it reclassifies a party for collection
   * purposes and touches no balance or record.
   */
  @Patch(':id/collection-frequency')
  updateCollectionFrequency(
    @Req() req,
    @Headers() headers,
    @Param('id') id: string,
    @Body() dto: UpdateCollectionFrequencyDto,
  ) {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.updateCollectionFrequency(
      id,
      businessId,
      dto.collectionFrequency,
    );
  }

  /**
   * Deletion cascades away every bill, ledger entry and balance-history row
   * for the party (see PartyService.delete), so it is restricted to
   * OWNER/MANAGER rather than open to every business member.
   */
  @Delete(':id')
  @Roles(UserRole.OWNER, UserRole.MANAGER)
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteParty(@Req() req, @Headers() headers, @Param('id') id: string) {
    const businessId = this.extractBusinessId(req, headers);
    return this.partyService.delete(id, businessId);
  }
}
