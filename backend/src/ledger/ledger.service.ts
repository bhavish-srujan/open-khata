import { Injectable, NotFoundException, BadRequestException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { LedgerEntry } from './ledger-entry.entity';
import { Party } from '../party/party.entity';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';
import { BalanceHistory } from './balance-history.entity';

@Injectable()
export class LedgerService {
  constructor(
    @InjectRepository(LedgerEntry)
    private readonly ledgerRepository: Repository<LedgerEntry>,
    @InjectRepository(Party)
    private readonly partyRepository: Repository<Party>,
    private dataSource: DataSource,
  ) {}

  findByParty(partyId: string, businessId: string): Promise<LedgerEntry[]> {
    return this.ledgerRepository.find({
      where: { partyId, businessId },
      order: { createdAt: 'DESC' },
    });
  }

  async create(businessId: string, dto: CreateLedgerEntryDto): Promise<LedgerEntry> {
    return this.dataSource.transaction(async (manager) => {
      // 1. Lock the party row to prevent race conditions during balance update
      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', { id: dto.partyId, businessId })
        .getOne();

      if (!party) {
        throw new NotFoundException(`Party ${dto.partyId} not found in business`);
      }

      const previousBalance = Number(party.currentBalance);

      // 2. Calculate delta
      const delta =
        party.type === 'CUSTOMER'
          ? dto.type === 'GAVE'
            ? dto.amount
            : -dto.amount
          : dto.type === 'GAVE'
            ? -dto.amount
            : dto.amount;

      const newBalance = previousBalance + Number(delta);

      // 3. Update party balance
      party.currentBalance = newBalance;
      await manager.save(party);

      // 4. Create Ledger Entry
      const entry = manager.create(LedgerEntry, {
        businessId,
        partyId: dto.partyId,
        amount: dto.amount,
        type: dto.type,
        note: dto.note ?? '',
        paymentMethod: dto.paymentMethod ?? 'Cash',
        source: (dto as any).source ?? 'MANUAL',
        status: 'CONFIRMED',
      });
      const savedEntry = await manager.save(entry);

      // 5. Create Balance History
      const history = manager.create(BalanceHistory, {
        businessId,
        partyId: dto.partyId,
        ledgerEntryId: savedEntry.id,
        previousBalance,
        newBalance,
      });
      await manager.save(history);

      return savedEntry;
    });
  }

  async delete(entryId: string, businessId: string): Promise<void> {
    return this.dataSource.transaction(async (manager) => {
      const entry = await manager.findOne(LedgerEntry, { where: { id: entryId, businessId } });
      if (!entry) throw new NotFoundException(`LedgerEntry ${entryId} not found`);

      const party = await manager
        .createQueryBuilder(Party, 'party')
        .setLock('pessimistic_write')
        .where('party.id = :id AND party.business_id = :businessId', { id: entry.partyId, businessId })
        .getOne();

      if (party) {
        const previousBalance = Number(party.currentBalance);
        
        const reverseDelta =
          party.type === 'CUSTOMER'
            ? entry.type === 'GAVE'
              ? -Number(entry.amount)
              : Number(entry.amount)
            : entry.type === 'GAVE'
              ? Number(entry.amount)
              : -Number(entry.amount);

        const newBalance = previousBalance + Number(reverseDelta);

        party.currentBalance = newBalance;
        await manager.save(party);

        // 5. Create Balance History for the deletion
        const history = manager.create(BalanceHistory, {
          businessId,
          partyId: entry.partyId,
          ledgerEntryId: null, // Because the entry is being deleted
          previousBalance,
          newBalance,
        });
        await manager.save(history);
      }

      await manager.remove(entry);
    });
  }
}
