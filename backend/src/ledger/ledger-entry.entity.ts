import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  ManyToOne,
  JoinColumn,
  Index,
} from 'typeorm';
import { Party } from '../party/party.entity';
import { Business } from '../business/business.entity';

@Entity('ledger_entries')
@Index('IDX_ledger_entries_business', ['businessId'])
@Index('IDX_ledger_entries_party', ['partyId'])
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

  /**
   * When the app recorded this row. Never user-editable — the audit trail for
   * when we learned about the transaction, including offline writes that sync
   * days later.
   */
  @CreateDateColumn()
  createdAt: Date;

  /**
   * When the transaction actually happened, as stated by the user.
   *
   * Separate from createdAt on purpose: entering yesterday's sale today must
   * land in yesterday's collection while still recording that the row was
   * created today. Every business-date query (daily/weekly/monthly reports,
   * statements) reads this column.
   *
   * Backfilled from created_at for rows that predate it, so no historical
   * entry changes the day it already appeared under.
   */
  @Index('IDX_ledger_entries_transaction_date')
  @Column({ name: 'transaction_date', type: 'timestamptz', default: () => 'now()' })
  transactionDate: Date;
}
