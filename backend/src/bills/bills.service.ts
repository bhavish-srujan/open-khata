import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Bill } from './entities/bill.entity';
import { CreateBillDto } from './dto/create-bill.dto';
import { Party } from '../party/party.entity';

/** Postgres unique_violation. */
const POSTGRES_UNIQUE_VIOLATION = '23505';

@Injectable()
export class BillsService {
  constructor(
    @InjectRepository(Bill)
    private readonly billsRepository: Repository<Bill>,
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
  ) {}

  /**
   * The client may supply its own id (offline-first, same pattern as parties
   * and ledger entries). Treat a repeat of an id we already hold as a
   * replayed request and return the stored row unchanged, so a retried
   * create neither duplicates the bill nor double-counts it in Sales.
   *
   * The pre-check below is an optimisation for the common sequential-retry
   * case. It is NOT what makes concurrent duplicates safe — that guarantee
   * comes from the bills.id primary key, enforced by Postgres regardless of
   * what this check observes. See the catch in create() for the case where
   * two requests race past this check at the same time.
   */
  private async findExistingForReplay(
    id: string,
    businessId: string,
  ): Promise<Bill | null> {
    const existing = await this.billsRepository.findOneBy({ id });
    if (!existing) return null;
    if (existing.businessId !== businessId) {
      // Bare 409: never confirm anything about another tenant's row.
      throw new ConflictException('Bill id is already in use');
    }
    return existing;
  }

  async create(
    createBillDto: CreateBillDto,
    businessId: string,
  ): Promise<Bill> {
    if (createBillDto.id) {
      const existing = await this.findExistingForReplay(
        createBillDto.id,
        businessId,
      );
      if (existing) return existing;
    }

    // partyId arrives from the client, so it must be checked against the
    // caller's business. Without this a bill could be raised against another
    // business's customer and would surface in that party's history.
    const party = await this.partyRepository.findOne({
      where: { id: createBillDto.partyId, businessId },
    });

    if (!party) {
      throw new NotFoundException(
        `Party ${createBillDto.partyId} not found in business`,
      );
    }

    const bill = this.billsRepository.create({
      ...createBillDto,
      // Applied last so a body field can never override the caller's tenancy.
      businessId,
    });

    try {
      return await this.billsRepository.save(bill);
    } catch (error) {
      // Two concurrent creates of the same id: the loser gets a unique
      // violation from the primary key index, not from this check.
      if (error?.code === POSTGRES_UNIQUE_VIOLATION && createBillDto.id) {
        const existing = await this.findExistingForReplay(
          createBillDto.id,
          businessId,
        );
        if (existing) return existing;
      }
      throw error;
    }
  }

  async findAll(businessId: string): Promise<Bill[]> {
    return this.billsRepository.find({ where: { businessId } });
  }

  async findOne(id: string, businessId: string): Promise<Bill | null> {
    return this.billsRepository.findOne({ where: { id, businessId } });
  }
}
