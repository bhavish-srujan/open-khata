import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Item } from './item.entity';
import { CreateItemDto, UpdateItemDto } from './dto/item.dto';

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

  async create(data: CreateItemDto, businessId: string): Promise<Item> {
    // businessId is applied last so a body field can never override it.
    const item = this.itemRepository.create({ ...data, businessId });
    return this.itemRepository.save(item);
  }

  /**
   * Applies only the editable fields.
   *
   * Deliberately not Object.assign: that copied whatever the request carried,
   * so `{"businessId": "..."}` moved the item into another business and
   * `{"id": "..."}` repointed the row. Tenancy and identity are not editable.
   */
  async update(id: string, data: UpdateItemDto, businessId: string): Promise<Item> {
    const item = await this.findOne(id, businessId);
    if (!item) throw new NotFoundException('Item not found');

    if (data.name !== undefined) item.name = data.name;
    if (data.sku !== undefined) item.sku = data.sku;
    if (data.sellingPrice !== undefined) item.sellingPrice = data.sellingPrice;
    if (data.purchasePrice !== undefined) item.purchasePrice = data.purchasePrice;
    if (data.currentStock !== undefined) item.currentStock = data.currentStock;
    if (data.lowStockThreshold !== undefined) {
      item.lowStockThreshold = data.lowStockThreshold;
    }

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
