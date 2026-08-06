import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
} from 'typeorm';

export enum AuditAction {
  CREATE = 'CREATE',
  UPDATE = 'UPDATE',
  DELETE = 'DELETE',
}

@Entity('audit_logs')
export class AuditLog {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'varchar', length: 50 })
  action: AuditAction;

  @Column()
  entityName: string;

  @Column()
  entityId: string;

  @Column({ name: 'performed_by' })
  performedBy: string;

  @Column({ type: 'jsonb', nullable: true })
  previousState: any;

  @Column({ type: 'jsonb', nullable: true })
  newState: any;

  @CreateDateColumn()
  createdAt: Date;
}
