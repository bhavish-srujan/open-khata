import { AsyncLocalStorage } from 'node:async_hooks';

export interface AuditContext {
  /** The authenticated user's id, once a guard has verified the token. */
  userId?: string;
}

/**
 * Per-request store for audit attribution.
 *
 * TypeORM instantiates subscribers itself (`new metadata.target()`) and gives
 * them no request context, so the subscriber cannot be handed the current user
 * through DI. AsyncLocalStorage carries it instead, using Node's built-in
 * async_hooks rather than an extra CLS dependency.
 */
export const auditContext = new AsyncLocalStorage<AuditContext>();

/**
 * Establishes an empty context for the lifetime of a request. Registered in
 * main.ts via app.use, so it wraps guards and handlers alike — a Nest
 * interceptor would not, because the route handler runs on subscription, after
 * the interceptor's synchronous scope has already exited.
 */
export function auditContextMiddleware(_req: any, _res: any, next: () => void) {
  auditContext.run({}, next);
}

/** Called by JwtAuthGuard once the token is verified. */
export function setAuditUser(userId: string): void {
  const store = auditContext.getStore();
  if (store) store.userId = userId;
}

/** The acting user, or undefined outside a request (cron jobs, seeds). */
export function getAuditUser(): string | undefined {
  return auditContext.getStore()?.userId;
}
