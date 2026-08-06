import { join } from 'path';

/**
 * Root directory exposed at /uploads/.
 *
 * Resolved from the working directory rather than __dirname. Multer's
 * destination is cwd-relative, but __dirname is dist/src once compiled, so
 * `join(__dirname, '..', 'uploads')` resolved to dist/uploads — a directory
 * nothing ever writes to, which made every uploaded receipt URL a 404.
 */
export const UPLOADS_ROOT = join(process.cwd(), 'uploads');

/**
 * Where receipt uploads are written. Must stay under UPLOADS_ROOT, otherwise
 * the URL handed back to the client cannot be served.
 */
export const RECEIPTS_DIR = join(UPLOADS_ROOT, 'receipts');
