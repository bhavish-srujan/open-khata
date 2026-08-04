import { Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, ManyToOne, JoinColumn } from 'typeorm';
import { Party } from '../party/party.entity';
import { LedgerEntry } from './ledger-entry.entity';
import { Business } from '../business/business.entity';

@Entity('balance_history')
export class BalanceHistory {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ name: 'business_id' })
  businessId: string;

  @ManyToOne(() => Business)
  @JoinColumn({ name: 'business_id' })
  business: Business;

  @Column({ name: 'party_id' })
  partyId: string;

  @ManyToOne(() => Party)
  @JoinColumn({ name: 'party_id' })
  party: Party;

  @Column({ name: 'ledger_entry_id', nullable: true })
  ledgerEntryId: string | null;

  @ManyToOne(() => LedgerEntry)
  @JoinColumn({ name: 'ledger_entry_id' })
  ledgerEntry: LedgerEntry;

  @Column({ type: 'decimal', precision: 14, scale: 2 })
  previousBalance: number;

  @Column({ type: 'decimal', precision: 14, scale: 2 })
  newBalance: number;

  @CreateDateColumn()
  createdAt: Date;
}
