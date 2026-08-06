import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
} from 'typeorm';
import { Business } from '../business/business.entity';

@Entity('items')
export class Item {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column()
  name: string;

  @Column({ nullable: true })
  sku: string;

  @Column({ type: 'decimal', precision: 14, scale: 2, default: 0 })
  sellingPrice: number;

  @Column({ type: 'decimal', precision: 14, scale: 2, default: 0 })
  purchasePrice: number;

  @Column({ type: 'decimal', precision: 14, scale: 2, default: 0 })
  currentStock: number;

  @Column({ type: 'decimal', precision: 14, scale: 2, default: 5 })
  lowStockThreshold: number;

  @Column({ name: 'business_id' })
  businessId: string;

  @ManyToOne(() => Business)
  @JoinColumn({ name: 'business_id' })
  business: Business;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
