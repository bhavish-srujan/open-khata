import {
  EventSubscriber,
  EntitySubscriberInterface,
  InsertEvent,
  UpdateEvent,
  RemoveEvent,
} from 'typeorm';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { Bill } from '../bills/entities/bill.entity';
import { AuditLog, AuditAction } from './audit-log.entity';

@EventSubscriber()
export class AuditLogSubscriber implements EntitySubscriberInterface {
  listenTo() {
    return 'all';
  }

  private shouldLog(entity: any): boolean {
    return entity instanceof LedgerEntry || entity instanceof Bill;
  }

  async afterInsert(event: InsertEvent<any>) {
    if (!this.shouldLog(event.entity)) return;

    const auditLog = new AuditLog();
    auditLog.action = AuditAction.CREATE;
    auditLog.entityName = event.entity.constructor.name;
    auditLog.entityId = event.entity.id;
    // Note: To capture who performed the action, a ClsService (Continuation-Local Storage) or passing userId directly is needed.
    // For simplicity, we denote SYSTEM if context is missing, or we assume a generic context.
    auditLog.performedBy = 'SYSTEM'; 
    auditLog.newState = event.entity;
    
    await event.manager.save(AuditLog, auditLog);
  }

  async afterUpdate(event: UpdateEvent<any>) {
    if (!this.shouldLog(event.entity)) return;

    const auditLog = new AuditLog();
    auditLog.action = AuditAction.UPDATE;
    auditLog.entityName = event.entity?.constructor.name || event.metadata.name;
    auditLog.entityId = event.entity?.id || event.databaseEntity?.id;
    auditLog.performedBy = 'SYSTEM';
    auditLog.previousState = event.databaseEntity;
    auditLog.newState = event.entity;
    
    await event.manager.save(AuditLog, auditLog);
  }

  async afterRemove(event: RemoveEvent<any>) {
    if (!this.shouldLog(event.entity)) return;

    const auditLog = new AuditLog();
    auditLog.action = AuditAction.DELETE;
    auditLog.entityName = event.entity?.constructor.name || event.metadata.name;
    auditLog.entityId = event.entity?.id || event.databaseEntity?.id;
    auditLog.performedBy = 'SYSTEM';
    auditLog.previousState = event.databaseEntity || event.entity;
    
    await event.manager.save(AuditLog, auditLog);
  }
}
