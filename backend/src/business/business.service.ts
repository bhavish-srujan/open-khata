import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Business } from './business.entity';
import { UpdateBusinessDto } from './dto/business.dto';

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

  /**
   * Applies only the editable fields.
   *
   * Deliberately not Object.assign: that copied whatever the request carried,
   * so `{"ownerId": "..."}` reassigned the business to another user and
   * `{"id": "..."}` repointed the row. Ownership and identity are set at
   * creation and are not editable.
   */
  async update(
    id: string,
    ownerId: string,
    updateData: UpdateBusinessDto,
  ): Promise<Business> {
    const business = await this.findOne(id, ownerId);

    if (updateData.name !== undefined) business.name = updateData.name;
    if (updateData.type !== undefined) business.type = updateData.type;
    if (updateData.gstNumber !== undefined) business.gstNumber = updateData.gstNumber;

    return this.businessRepository.save(business);
  }

  async delete(id: string, ownerId: string): Promise<void> {
    const business = await this.findOne(id, ownerId);
    await this.businessRepository.remove(business);
  }
}
