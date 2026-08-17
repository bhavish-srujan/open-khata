import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  UpdateDateColumn,
  ManyToOne,
  JoinColumn,
} from 'typeorm';
import { User } from '../users/user.entity';

@Entity('businesses')
export class Business {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ name: 'owner_id' })
  ownerId: string;

  @ManyToOne(() => User)
  @JoinColumn({ name: 'owner_id' })
  owner: User;

  @Column()
  name: string;

  @Column({ name: 'gst_number', nullable: true })
  gstNumber: string;

  @Column({ nullable: true })
  type: string;

  @Column({ nullable: true })
  address: string;

  @Column({ nullable: true })
  category: string;

  @Column({ name: 'bank_account', nullable: true })
  bankAccount: string;

  @Column({ name: 'profile_image_url', nullable: true })
  profileImageUrl: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt: Date;
}
