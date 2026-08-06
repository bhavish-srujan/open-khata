import { Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, UpdateDateColumn } from 'typeorm';

export enum UserRole {
  OWNER = 'OWNER',
  MANAGER = 'MANAGER',
  CASHIER = 'CASHIER',
}
@Entity('users')
export class User {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ unique: true })
  phone_number: string;

  @Column({ name: 'normalized_phone', type: 'varchar', unique: true, nullable: true })
  normalized_phone: string | null;

  @Column({ nullable: true })
  name: string;

  @Column({ type: 'varchar', nullable: true })
  currentOtp: string | null;

  @Column({
    type: 'enum',
    enum: UserRole,
    default: UserRole.OWNER,
  })
  role: UserRole;

  @Column({ type: 'timestamp', nullable: true })
  otpExpiry: Date | null;

  /**
   * Failed verification attempts against the current OTP. Reset when a new code
   * is issued or a login succeeds. Without this a six-digit code with a
   * ten-minute window can simply be enumerated.
   */
  @Column({ type: 'int', default: 0 })
  otpAttempts: number;

  @Column({ type: 'int', default: 1 })
  sessionVersion: number;

  @CreateDateColumn()
  created_at: Date;

  @UpdateDateColumn()
  updated_at: Date;
}
