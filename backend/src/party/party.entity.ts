import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
  Index,
} from 'typeorm';
import { Business } from '../business/business.entity';

@Entity('parties')
@Index('IDX_parties_business', ['businessId'])
export class Party {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column()
  name: string;

  @Column()
  phone: string;

  @Column({ type: 'varchar', default: 'CUSTOMER' })
  type: 'CUSTOMER' | 'SUPPLIER';

  @Column({ nullable: true })
  gstin: string;

  @Column({ type: 'decimal', precision: 14, scale: 2, default: 0 })
  currentBalance: number;

  @Column({ name: 'business_id' })
  businessId: string;

  @ManyToOne(() => Business)
  @JoinColumn({ name: 'business_id' })
  business: Business;

  @Column({ nullable: true })
  address: string;

  /**
   * Which collection bucket this party belongs to — Daily, Weekly or Monthly.
   *
   * This is the user's own classification of the party, not anything derived
   * from dates or activity. It previously existed only in the client's Room
   * database and was never sent here, so restoring a device had nothing to
   * restore it *from* and every party came back as DAILY.
   *
   * Defaults to DAILY, which is what a row without this information already
   * behaved as, so existing parties are unchanged by its introduction.
   */
  @Column({
    name: 'collection_frequency',
    type: 'varchar',
    length: 10,
    default: 'DAILY',
  })
  collectionFrequency: 'DAILY' | 'WEEKLY' | 'MONTHLY';

  /**
   * Interest configuration is per-party, optional, and never annualized or
   * auto-converted: interestRate applies as-is to whatever
   * interestPeriodValue/interestPeriodUnit says (e.g. "5% / 100 Days" means
   * exactly that, not a daily or yearly rate). Defaults leave every existing
   * party with interest off and zero-valued fields — created before this
   * column existed, none of them silently start accruing anything.
   */
  @Column({ name: 'interest_enabled', type: 'boolean', default: false })
  interestEnabled: boolean;

  @Column({
    name: 'interest_rate',
    type: 'decimal',
    precision: 8,
    scale: 3,
    default: 0,
  })
  interestRate: number;

  @Column({ name: 'interest_period_value', type: 'int', default: 0 })
  interestPeriodValue: number;

  @Column({
    name: 'interest_period_unit',
    type: 'varchar',
    length: 10,
    default: 'DAYS',
  })
  interestPeriodUnit: 'DAYS' | 'MONTHS';

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
