import { ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { DataSource, Repository } from 'typeorm';
import { Business } from './business.entity';
import { UpdateBusinessDto } from './dto/business.dto';
import { BusinessMember } from './business-member.entity';
import { Party } from '../party/party.entity';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { Bill } from '../bills/entities/bill.entity';
import { Item } from '../inventory/item.entity';
import { Receipt } from '../uploads/receipt.entity';
import { UserRole } from '../users/user.entity';

@Injectable()
export class BusinessService {
  constructor(
    @InjectRepository(Business)
    private businessRepository: Repository<Business>,
    private readonly dataSource: DataSource,
  ) {}

  /**
   * Creates a business and the owner's membership of it.
   *
   * Both, together: access is resolved from business_members, so a business
   * created without one would be invisible to the person who just made it.
   */
  async create(ownerId: string, name: string, type?: string, gstNumber?: string): Promise<Business> {
    return this.dataSource.transaction(async (manager) => {
      const business = await manager.save(
        manager.create(Business, { ownerId, name, type, gstNumber }),
      );

      await manager.save(
        manager.create(BusinessMember, {
          businessId: business.id,
          userId: ownerId,
          role: UserRole.OWNER,
        }),
      );

      return business;
    });
  }

  /**
   * Every business the user can act in, owned or not.
   *
   * Listing by ownerId alone hid businesses someone had been added to as
   * staff — they could authenticate into one but never see it listed.
   */
  async findAllForUser(userId: string): Promise<Business[]> {
    const memberships = await this.dataSource.getRepository(BusinessMember).find({
      where: { userId },
      relations: { business: true },
      order: { createdAt: 'ASC' },
    });

    return memberships
      .map((m) => m.business)
      .filter((b): b is Business => !!b);
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

  /**
   * Deletes a business, but only an empty one.
   *
   * Seven tables reference businesses with ON DELETE NO ACTION, so this
   * previously returned 500 for any business that had ever been used — the
   * third instance of that fault after ledger entries and parties.
   *
   * Refusing is the fix rather than cascading: a business holds the entire
   * bookkeeping history of a shop, and silently destroying every party, entry,
   * bill and receipt because someone tapped delete is not a recoverable
   * mistake. The caller must clear the data deliberately first.
   */
  async delete(id: string, ownerId: string): Promise<void> {
    const business = await this.findOne(id, ownerId);

    const [parties, entries, bills, items, receipts] = await Promise.all([
      this.dataSource.getRepository(Party).count({ where: { businessId: id } }),
      this.dataSource.getRepository(LedgerEntry).count({ where: { businessId: id } }),
      this.dataSource.getRepository(Bill).count({ where: { businessId: id } }),
      this.dataSource.getRepository(Item).count({ where: { businessId: id } }),
      this.dataSource.getRepository(Receipt).count({ where: { businessId: id } }),
    ]);

    const held = parties + entries + bills + items + receipts;
    if (held > 0) {
      throw new ConflictException(
        `This business still holds ${held} record(s) — ${parties} parties, ` +
          `${entries} ledger entries, ${bills} bills, ${items} items, ` +
          `${receipts} receipts. Remove them before deleting the business.`,
      );
    }

    await this.dataSource.transaction(async (manager) => {
      // Memberships are bookkeeping for access, not business data, so they go
      // with the business rather than blocking its removal.
      await manager.delete(BusinessMember, { businessId: id });
      await manager.remove(business);
    });
  }
}
