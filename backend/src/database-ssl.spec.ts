import { buildDatabaseSsl } from './database-ssl';

/**
 * TLS policy for the database connection.
 *
 * Supabase's chain ends in a self-signed "Supabase Root 2021 CA", so an
 * unconfigured client fails with SELF_SIGNED_CERT_IN_CHAIN. The shortcuts —
 * rejectUnauthorized: false or NODE_TLS_REJECT_UNAUTHORIZED=0 — stop the client
 * authenticating the server at all, so this asserts the opposite: verification
 * stays on and the specific root is trusted.
 */
describe('database TLS policy', () => {
  const originalCa = process.env.DATABASE_SSL_CA;

  afterEach(() => {
    if (originalCa === undefined) delete process.env.DATABASE_SSL_CA;
    else process.env.DATABASE_SSL_CA = originalCa;
  });

  describe('local development', () => {
    it.each([
      'postgresql://postgres:password@localhost:5432/khatabook',
      'postgresql://postgres:password@127.0.0.1:5432/khatabook',
      'postgresql://postgres:password@db:5432/khatabook',
    ])('connects in the clear to %s', (url) => {
      expect(buildDatabaseSsl(url)).toBe(false);
    });
  });

  describe('remote databases', () => {
    const remote =
      'postgresql://user:pw@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres';

    it('requires TLS', () => {
      expect(buildDatabaseSsl(remote)).not.toBe(false);
    });

    it('keeps certificate verification enabled', () => {
      const ssl = buildDatabaseSsl(remote) as any;

      // The whole point: this must never be false.
      expect(ssl.rejectUnauthorized).toBe(true);
    });

    it('supplies the Supabase root so the chain can be verified', () => {
      const ssl = buildDatabaseSsl(remote) as any;

      expect(ssl.ca).toContain('BEGIN CERTIFICATE');
    });

    it('accepts an inline PEM override for other providers', () => {
      process.env.DATABASE_SSL_CA =
        '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----';

      const ssl = buildDatabaseSsl(remote) as any;

      expect(ssl.ca).toContain('MIIB');
      expect(ssl.rejectUnauthorized).toBe(true);
    });

    it('rejects a DATABASE_SSL_CA that is neither a PEM nor a readable file', () => {
      process.env.DATABASE_SSL_CA = '/no/such/certificate.crt';

      expect(() => buildDatabaseSsl(remote)).toThrow(/neither a PEM/);
    });
  });

  it('never disables verification globally', () => {
    // A stray NODE_TLS_REJECT_UNAUTHORIZED=0 would silently switch off
    // certificate checking for every outbound TLS connection in the process,
    // including the SMS gateway and Gemini.
    expect(process.env.NODE_TLS_REJECT_UNAUTHORIZED).not.toBe('0');
  });
});
