import { Injectable } from '@nestjs/common';
import {
  DataSource,
  EventSubscriber,
  EntitySubscriberInterface,
  InsertEvent,
  UpdateEvent,
  RemoveEvent,
} from 'typeorm';
import { LedgerEntry } from '../ledger/ledger-entry.entity';
import { Bill } from '../bills/entities/bill.entity';
import { AuditLog, AuditAction } from './audit-log.entity';
import { getAuditUser } from './audit-context';

/** Recorded when a write happens outside a request, e.g. the reminder cron. */
const SYSTEM_ACTOR = 'SYSTEM';

/**
 * Writes an audit row for every LedgerEntry and Bill mutation.
 *
 * Two independent faults previously stopped this from ever running:
 *
 *  1. It was only a Nest provider. TypeORM builds its subscriber list from
 *     `options.subscribers`, which app.module.ts does not set, so the class was
 *     never attached to the DataSource. Registration now happens in the
 *     constructor — the documented Nest pattern, and necessary because TypeORM
 *     would otherwise construct it via `new metadata.target()` with no DI.
 *
 *  2. It declared `listenTo() { return 'all' }`. TypeORM's isAllowedSubscriber
 *     accepts a falsy return, `Object`, the target class, or a prototype of it.
 *     The string 'all' is none of those, so every entity was rejected. Omitting
 *     listenTo entirely is what actually means "all entities".
 */
@Injectable()
@EventSubscriber()
export class AuditLogSubscriber implements EntitySubscriberInterface {
  constructor(dataSource: DataSource) {
    dataSource.subscribers.push(this);
  }

  // No listenTo(): a falsy result is how TypeORM expresses "every entity".

  private shouldLog(entity: any): boolean {
    return entity instanceof LedgerEntry || entity instanceof Bill;
  }

  /**
   * The authenticated user for the in-flight request, or SYSTEM when there is
   * none (cron jobs, scripts, migrations).
   */
  private performedBy(): string {
    return getAuditUser() ?? SYSTEM_ACTOR;
  }

  async afterInsert(event: InsertEvent<any>) {
    if (!this.shouldLog(event.entity)) return;

    const auditLog = new AuditLog();
    auditLog.action = AuditAction.CREATE;
    auditLog.entityName = event.metadata.name;
    auditLog.entityId = event.entity.id;
    auditLog.performedBy = this.performedBy();
    auditLog.newState = event.entity;

    await event.manager.save(AuditLog, auditLog);
  }

  async afterUpdate(event: UpdateEvent<any>) {
    if (!this.shouldLog(event.entity ?? event.databaseEntity)) return;

    const auditLog = new AuditLog();
    auditLog.action = AuditAction.UPDATE;
    auditLog.entityName = event.metadata.name;
    auditLog.entityId = event.entity?.id ?? event.databaseEntity?.id;
    auditLog.performedBy = this.performedBy();
    auditLog.previousState = event.databaseEntity;
    auditLog.newState = event.entity;

    await event.manager.save(AuditLog, auditLog);
  }

  async afterRemove(event: RemoveEvent<any>) {
    if (!this.shouldLog(event.entity ?? event.databaseEntity)) return;

    const auditLog = new AuditLog();
    auditLog.action = AuditAction.DELETE;
    auditLog.entityName = event.metadata.name;
    auditLog.entityId = event.entity?.id ?? event.databaseEntity?.id;
    auditLog.performedBy = this.performedBy();
    auditLog.previousState = event.databaseEntity ?? event.entity;

    await event.manager.save(AuditLog, auditLog);
  }
}
