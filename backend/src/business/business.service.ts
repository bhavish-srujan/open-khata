import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Business } from './business.entity';

@Injectable()
export class BusinessService {
  constructor(
    @InjectRepository(Business)
    private businessRepository: Repository<Business>,
  ) {}

  async create(ownerId: string, name: string, type?: string, gstNumber?: string): Promise<Business> {
    const business = this.businessRepository.create({
      ownerId,
      name,
      type,
      gstNumber,
    });
    return this.businessRepository.save(business);
  }

  async findAllByOwner(ownerId: string): Promise<Business[]> {
    return this.businessRepository.find({ where: { ownerId } });
  }

  async findOne(id: string, ownerId: string): Promise<Business> {
    const business = await this.businessRepository.findOne({ where: { id, ownerId } });
    if (!business) {
      throw new NotFoundException('Business not found');
    }
    return business;
  }

  async update(id: string, ownerId: string, updateData: Partial<Business>): Promise<Business> {
    const business = await this.findOne(id, ownerId);
    Object.assign(business, updateData);
    return this.businessRepository.save(business);
  }

  async delete(id: string, ownerId: string): Promise<void> {
    const business = await this.findOne(id, ownerId);
    await this.businessRepository.remove(business);
  }
}
