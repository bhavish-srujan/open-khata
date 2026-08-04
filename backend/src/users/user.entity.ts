import { Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, UpdateDateColumn } from 'typeorm';

@Entity('users')
export class User {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ unique: true })
  phone_number: string;

  @Column({ nullable: true })
  name: string;

  @Column({ type: 'varchar', nullable: true })
  currentOtp: string | null;

  @Column({ type: 'timestamp', nullable: true })
  otpExpiry: Date | null;

  @CreateDateColumn()
  created_at: Date;

  @UpdateDateColumn()
  updated_at: Date;
}
