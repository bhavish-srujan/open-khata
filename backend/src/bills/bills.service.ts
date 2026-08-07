import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Bill } from './entities/bill.entity';
import { CreateBillDto } from './dto/create-bill.dto';
import { Party } from '../party/party.entity';

@Injectable()
export class BillsService {
  constructor(
    @InjectRepository(Bill)
    private readonly billsRepository: Repository<Bill>,
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
  ) {}

  async create(createBillDto: CreateBillDto, businessId: string): Promise<Bill> {
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
    return this.billsRepository.save(bill);
  }

  async findAll(businessId: string): Promise<Bill[]> {
    return this.billsRepository.find({ where: { businessId } });
  }

  async findOne(id: string, businessId: string): Promise<Bill | null> {
    return this.billsRepository.findOne({ where: { id, businessId } });
  }
}
