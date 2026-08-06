import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  ManyToOne,
  JoinColumn,
} from 'typeorm';
import { Party } from '../party/party.entity';
import { Business } from '../business/business.entity';

@Entity('ledger_entries')
export class LedgerEntry {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ nullable: true })
  receiptUrl: string;

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

  @Column({ type: 'decimal', precision: 14, scale: 2 })
  amount: number;

  @Column({ type: 'varchar' })
  type: 'GAVE' | 'GOT';

  @Column({ nullable: true, default: '' })
  note: string;

  @Column({ default: 'Cash' })
  paymentMethod: string;

  @Column({ type: 'varchar', default: 'MANUAL' })
  source: 'MANUAL' | 'OCR';

  @Column({ type: 'varchar', default: 'CONFIRMED' })
  status: 'PENDING_REVIEW' | 'CONFIRMED' | 'REJECTED';

  @CreateDateColumn()
  createdAt: Date;
}
