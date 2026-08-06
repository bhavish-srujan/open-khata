import { IsDateString, IsOptional } from 'class-validator';

/**
 * Query for the business-wide ledger feed.
 *
 * Deliberately string-only: the global ValidationPipe runs without
 * `transform`, so a numeric query param would arrive as a string and fail
 * @IsInt. Row count is capped in the service instead of by the caller.
 */
export class ListLedgerQueryDto {
  /** Inclusive ISO lower bound on createdAt. */
  @IsOptional()
  @IsDateString()
  from?: string;

  /** Inclusive ISO upper bound on createdAt. */
  @IsOptional()
  @IsDateString()
  to?: string;
}
