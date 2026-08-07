import { existsSync, readFileSync } from 'fs';
import { join } from 'path';

/**
 * TLS settings for the Postgres connection.
 *
 * Supabase terminates its chain in a self-signed "Supabase Root 2021 CA" that
 * is not in Node's trust store, so an unconfigured connection fails with
 * SELF_SIGNED_CERT_IN_CHAIN. The tempting fixes — rejectUnauthorized: false, or
 * NODE_TLS_REJECT_UNAUTHORIZED=0 — disable authentication of the server
 * entirely and expose the connection to interception. Instead we trust that one
 * root explicitly and keep verification on.
 *
 * Root fingerprint (SHA-256), verifiable against the certificate Supabase
 * publishes in the dashboard under Database > SSL Configuration:
 *   80:70:25:AD:50:D4:ED:21:9D:2C:9C:7D:29:9C:00:4F:82:4E:B0:0C:F7:F6:5A:FE:F6:07:D0:7B:72:E6:CA:FA
 */

/**
 * Bundled root, overridable via DATABASE_SSL_CA.
 *
 * Looked up by candidate rather than a single path: __dirname is src/ under
 * ts-node but dist/src/ once compiled, so a lone relative path resolves
 * somewhere different at runtime than in development.
 */
const BUNDLED_CA_CANDIDATES = [
  join(process.cwd(), 'certs', 'supabase-root-2021.crt'),
  join(__dirname, '..', 'certs', 'supabase-root-2021.crt'),
  join(__dirname, '..', '..', 'certs', 'supabase-root-2021.crt'),
];

function isLocalHost(databaseUrl: string): boolean {
  try {
    const { hostname } = new URL(databaseUrl);
    return (
      hostname === 'localhost' ||
      hostname === '127.0.0.1' ||
      hostname === '::1' ||
      hostname === 'db' // docker-compose service name
    );
  } catch {
    return false;
  }
}

/**
 * Resolves the CA bundle: an inline PEM or a file path in DATABASE_SSL_CA, else
 * the bundled Supabase root.
 */
function resolveCa(): string | undefined {
  const configured = process.env.DATABASE_SSL_CA?.trim();

  if (configured) {
    if (configured.includes('BEGIN CERTIFICATE')) return configured;
    if (existsSync(configured)) return readFileSync(configured, 'utf8');
    throw new Error(
      'DATABASE_SSL_CA is set but is neither a PEM certificate nor a readable file path',
    );
  }

  const bundled = BUNDLED_CA_CANDIDATES.find((p) => existsSync(p));
  return bundled ? readFileSync(bundled, 'utf8') : undefined;
}

/**
 * TypeORM `ssl` option for the given connection string.
 *
 * Local databases connect in the clear, which is how docker-compose runs. Every
 * remote database gets TLS with the server certificate verified.
 */
export function buildDatabaseSsl(
  databaseUrl: string,
): false | { ca: string; rejectUnauthorized: true } {
  if (isLocalHost(databaseUrl)) return false;

  const ca = resolveCa();
  if (!ca) {
    // Failing closed: a remote connection without a trustable CA would either
    // refuse to connect or, worse, tempt someone into turning verification off.
    throw new Error(
      'A remote DATABASE_URL requires a CA certificate. Provide DATABASE_SSL_CA ' +
        'or restore certs/supabase-root-2021.crt.',
    );
  }

  return { ca, rejectUnauthorized: true };
}
