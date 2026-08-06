import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, Between } from 'typeorm';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { Bill } from '../bills/entities/bill.entity';
import { Party } from '../party/party.entity';

@Injectable()
export class ReportsService {
  constructor(
    @InjectRepository(LedgerEntry)
    private readonly ledgerRepository: Repository<LedgerEntry>,
    @InjectRepository(Bill)
    private readonly billRepository: Repository<Bill>,
    // Assuming Party repository is needed for party details
  ) {}

  async getLedgerEntries(businessId: string, partyId?: string, startDate?: Date, endDate?: Date): Promise<LedgerEntry[]> {
    const where: any = { businessId };
    if (partyId) where.partyId = partyId;
    if (startDate && endDate) {
      where.createdAt = Between(startDate, endDate);
    }
    
    return this.ledgerRepository.find({
      where,
      order: { createdAt: 'ASC' },
      relations: { party: true }
    });
  }

  async getCashflow(businessId: string, startDate: Date, endDate: Date) {
    const entries = await this.ledgerRepository.find({
      where: {
        businessId,
        createdAt: Between(startDate, endDate)
      }
    });

    let totalIn = 0;
    let totalOut = 0;

    for (const entry of entries) {
      if (entry.type === 'GOT') {
        totalIn += Number(entry.amount);
      } else if (entry.type === 'GAVE') {
        totalOut += Number(entry.amount);
      }
    }

    return { totalIn, totalOut, net: totalIn - totalOut };
  }

  async getSales(businessId: string, startDate: Date, endDate: Date) {
    const bills = await this.billRepository.find({
      where: {
        businessId,
        created_at: Between(startDate, endDate)
      }
    });

    let totalSales = 0;
    let gstSales = 0;
    let nonGstSales = 0;

    for (const bill of bills) {
      totalSales += Number(bill.totalAmount);
      if (bill.isGst) {
        gstSales += Number(bill.totalAmount);
      } else {
        nonGstSales += Number(bill.totalAmount);
      }
    }

    return { totalSales, gstSales, nonGstSales };
  }
}
