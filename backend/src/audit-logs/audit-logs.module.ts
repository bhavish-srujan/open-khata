import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AuditLogsService } from './audit-logs.service';
import { AuditLog } from './audit-log.entity';
import { AuditLogSubscriber } from './audit-log.subscriber';

@Module({
  imports: [TypeOrmModule.forFeature([AuditLog])],
  providers: [AuditLogsService, AuditLogSubscriber],
  exports: [AuditLogsService]
})
export class AuditLogsModule {}
