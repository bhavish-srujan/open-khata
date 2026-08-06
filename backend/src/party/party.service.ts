import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Party } from './party.entity';
import { CreatePartyDto } from './dto/create-party.dto';
import { SmsService } from '../notifications/sms/sms.service';

@Injectable()
export class PartyService {
  constructor(
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    private readonly smsService: SmsService,
  ) {}

  findAll(businessId: string, type?: 'CUSTOMER' | 'SUPPLIER'): Promise<Party[]> {
    const where: any = { businessId };
    if (type) {
      where.type = type;
    }
    return this.partyRepository.find({ where, order: { createdAt: 'DESC' } });
  }

  async findOne(id: string, businessId: string): Promise<Party> {
    const party = await this.partyRepository.findOneBy({ id, businessId });
    if (!party) throw new NotFoundException(`Party ${id} not found in business ${businessId}`);
    return party;
  }

  async create(businessId: string, dto: CreatePartyDto): Promise<Party> {
    const party = this.partyRepository.create({
      name: dto.name,
      phone: dto.phone,
      type: dto.type,
      gstin: dto.gstin,
      businessId,
      currentBalance: 0,
    });
    const savedParty = await this.partyRepository.save(party);

    // Send welcome SMS if phone number is provided
    if (savedParty.phone) {
      await this.smsService.sendSms(
        savedParty.phone,
        `Welcome to Khata Book! Your account has been created by ${businessId}.`,
      );
    }

    return savedParty;
  }

  async delete(id: string, businessId: string): Promise<void> {
    const party = await this.findOne(id, businessId);
    await this.partyRepository.remove(party);
  }

  /** Called by LedgerService to atomically update the running balance. We shouldn't use findOne + update to avoid race conditions. */
  async updateBalance(partyId: string, businessId: string, delta: number): Promise<void> {
    // A better approach for atomicity is using QueryBuilder increment/decrement, but since we use transactions in Ledger, we might just pass the transaction entity manager.
    // However, for MVP, we'll use a direct update with increment.
    await this.partyRepository.increment({ id: partyId, businessId }, 'currentBalance', delta);
  }
}
