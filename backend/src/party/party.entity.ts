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

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
