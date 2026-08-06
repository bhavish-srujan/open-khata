import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Item } from './item.entity';

@Injectable()
export class InventoryService {
  constructor(
    @InjectRepository(Item)
    private readonly itemRepository: Repository<Item>,
  ) {}

  async findAll(businessId: string): Promise<Item[]> {
    return this.itemRepository.find({ where: { businessId } });
  }

  async findOne(id: string, businessId: string): Promise<Item | null> {
    return this.itemRepository.findOne({ where: { id, businessId } });
  }

  async create(data: Partial<Item>, businessId: string): Promise<Item> {
    const item = this.itemRepository.create({ ...data, businessId });
    return this.itemRepository.save(item);
  }

  async update(id: string, data: Partial<Item>, businessId: string): Promise<Item> {
    const item = await this.findOne(id, businessId);
    if (!item) throw new NotFoundException('Item not found');
    Object.assign(item, data);
    return this.itemRepository.save(item);
  }

  async delete(id: string, businessId: string): Promise<void> {
    const item = await this.findOne(id, businessId);
    if (!item) throw new NotFoundException('Item not found');
    await this.itemRepository.remove(item);
  }

  async getLowStockAlerts(businessId: string): Promise<Item[]> {
    const items = await this.findAll(businessId);
    return items.filter(item => item.currentStock <= item.lowStockThreshold);
  }
}
