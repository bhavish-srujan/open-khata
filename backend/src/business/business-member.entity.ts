import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
  Unique,
} from 'typeorm';
import { Business } from './business.entity';
import { User } from '../users/user.entity';
import { UserRole } from '../users/user.entity';

/**
 * A user's membership of, and role within, one business.
 *
 * Role previously lived as a single global column on User, so it could not
 * differ between businesses and there was nothing tying a user to a business at
 * all. Staff listings therefore returned every MANAGER and CASHIER in the
 * database, and a user added as staff had no way to reach the business they
 * were added to.
 *
 * This table is the tenancy boundary for people, mirroring what business_id
 * already does for parties, entries and receipts.
 */
@Entity('business_members')
@Unique('UQ_business_members_business_user', ['businessId', 'userId'])
export class BusinessMember {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ name: 'business_id' })
  businessId: string;

  @ManyToOne(() => Business, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'business_id' })
  business: Business;

  @Column({ name: 'user_id' })
  userId: string;

  @ManyToOne(() => User, { onDelete: 'CASCADE' })
  @JoinColumn({ name: 'user_id' })
  user: User;

  /** Role held in *this* business, not globally. */
  @Column({ type: 'varchar', length: 20 })
  role: UserRole;

  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt: Date;
}
