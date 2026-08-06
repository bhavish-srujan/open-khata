import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Bill } from './entities/bill.entity';
import { CreateBillDto } from './dto/create-bill.dto';

@Injectable()
export class BillsService {
  constructor(
    @InjectRepository(Bill)
    private readonly billsRepository: Repository<Bill>,
  ) {}

  async create(createBillDto: CreateBillDto, businessId: string): Promise<Bill> {
    const bill = this.billsRepository.create({
      ...createBillDto,
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
