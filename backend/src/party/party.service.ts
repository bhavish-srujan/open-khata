import { ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Party } from './party.entity';
import { Business } from '../business/business.entity';
import { CreatePartyDto } from './dto/create-party.dto';
import { SmsService } from '../notifications/sms/sms.service';

/** Postgres unique_violation. */
const POSTGRES_UNIQUE_VIOLATION = '23505';

@Injectable()
export class PartyService {
  constructor(
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    // Resolvable anywhere: AuthModule is @Global and re-exports TypeOrmModule.
    @InjectRepository(Business)
    private readonly businessRepository: Repository<Business>,
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
    // The client may supply its own id (offline-first). Treat a repeat of an id
    // we already hold as a replayed request and return the stored row unchanged,
    // so retries neither duplicate the party nor re-send the welcome SMS.
    if (dto.id) {
      const existing = await this.findExistingForReplay(dto.id, businessId);
      if (existing) return existing;
    }

    const party = this.partyRepository.create({
      id: dto.id,
      name: dto.name,
      phone: dto.phone,
      type: dto.type,
      gstin: dto.gstin,
      businessId,
      currentBalance: 0,
    });

    let savedParty: Party;
    try {
      savedParty = await this.partyRepository.save(party);
    } catch (error) {
      // Two concurrent creates of the same id: the loser gets a unique violation.
      if (error?.code === POSTGRES_UNIQUE_VIOLATION && dto.id) {
        const existing = await this.findExistingForReplay(dto.id, businessId);
        if (existing) return existing;
      }
      throw error;
    }

    // Send welcome SMS if phone number is provided
    if (savedParty.phone) {
      // The business name, not its id. Customers previously received
      // "…created by 3f1a9c22-0000-4000-8000-000000000001".
      const business = await this.businessRepository.findOne({
        where: { id: businessId },
      });
      const businessName = business?.name?.trim() || 'your shop';

      await this.smsService.sendSms(
        savedParty.phone,
        `Welcome to ${businessName}! Your Khata Book account has been created.`,
      );
    }

    return savedParty;
  }

  /**
   * Resolves a client-supplied id to an existing row for idempotent replay.
   *
   * Returns the row when it already belongs to this business. If the id exists
   * under a *different* business we reject with a bare 409 — echoing anything
   * about that row would turn client-chosen ids into a cross-tenant existence
   * oracle. Returns null when the id is genuinely new.
   */
  private async findExistingForReplay(
    id: string,
    businessId: string,
  ): Promise<Party | null> {
    const existing = await this.partyRepository.findOneBy({ id });
    if (!existing) return null;
    if (existing.businessId !== businessId) {
      throw new ConflictException('Party id is already in use');
    }
    return existing;
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
