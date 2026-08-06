import { Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, UpdateDateColumn, ManyToOne, JoinColumn } from 'typeorm';
import { Business } from '../../business/business.entity';
import { Party } from '../../party/party.entity';
import { LedgerEntry } from '../../ledger/ledger-entry.entity';

@Entity('bills')
export class Bill {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column()
  businessId: string;

  @ManyToOne(() => Business)
  @JoinColumn({ name: 'businessId' })
  business: Business;

  @Column()
  partyId: string;

  @ManyToOne(() => Party)
  @JoinColumn({ name: 'partyId' })
  party: Party;

  @Column({ type: 'decimal', precision: 10, scale: 2 })
  totalAmount: number;

  @Column({ type: 'jsonb', nullable: true })
  items: any; // { description, quantity, price, tax }

  @Column({ nullable: true })
  ledgerEntryId: string;

  @ManyToOne(() => LedgerEntry)
  @JoinColumn({ name: 'ledgerEntryId' })
  ledgerEntry: LedgerEntry;

  @Column({ default: false })
  isGst: boolean;

  @Column({ nullable: true })
  receiptUrl: string;

  @CreateDateColumn()
  created_at: Date;

  @UpdateDateColumn()
  updated_at: Date;
}
