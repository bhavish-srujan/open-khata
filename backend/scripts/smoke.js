/**
 * End-to-end smoke test against a real Postgres.
 *
 * Exists because the unit suite stubs the entity manager, so it cannot see
 * database constraints. Deleting a ledger entry returned 500 for the life of
 * the project — balance_history references the entry ON DELETE NO ACTION — and
 * every unit test passed regardless. Anything touching foreign keys,
 * transactions or generated columns needs a real database to be believed.
 *
 *   npm run smoke                 local database only
 *   SMOKE_PROD=1 npm run smoke    additionally boots against the production
 *                                 database, read-only, to check TLS, schema
 *                                 and required env
 *
 * SMS_GATEWAY_URL is blanked throughout, so nothing reaches a real phone.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const LOCAL_URL = 'postgresql://postgres:password@localhost:5432/khatabook';
const TABLES = [
  'balance_history', 'audit_logs', 'ledger_entries', 'parties',
  'items', 'bills', 'business_members', 'businesses', 'users',
];

function hostedUrl() {
  const line = fs
    .readFileSync(path.join(ROOT, '.env'), 'utf8')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .find((s) => s.startsWith('# DATABASE_URL=') && !s.includes('localhost'));
  if (!line) throw new Error('no hosted DATABASE_URL found commented in .env');
  return line.replace(/^#\s*DATABASE_URL=/, '').trim().replace(/^["']|["']$/g, '');
}

async function boot(databaseUrl, nodeEnv) {
  process.env.DATABASE_URL = databaseUrl;
  process.env.NODE_ENV = nodeEnv;
  process.env.SMS_GATEWAY_URL = '';
  delete process.env.REMINDERS_ENABLED;

  for (const k of Object.keys(require.cache)) delete require.cache[k];

  const { NestFactory } = require('@nestjs/core');
  const { ValidationPipe } = require('@nestjs/common');
  const { AppModule } = require(path.join(ROOT, 'dist/src/app.module'));
  const { auditContextMiddleware } = require(
    path.join(ROOT, 'dist/src/audit-logs/audit-context'),
  );

  const app = await NestFactory.create(AppModule, { logger: false });
  app.use(auditContextMiddleware);
  app.useGlobalPipes(
    new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }),
  );
  await app.init();
  return app;
}

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

async function prodPass(request) {
  console.log('\nPROD-CONFIG (production mode, production database, read-only)\n');
  const app = await boot(hostedUrl(), 'production');
  const http = app.getHttpServer();

  record('boots in production mode against the production database', true,
    'TLS verified, schema accepted, required env present');

  const health = await request(http).get('/health');
  record('GET /health', health.status === 200, 'status ' + health.status);

  const ds = app.get(require('typeorm').DataSource);
  record('synchronize disabled', ds.options.synchronize === false,
    'synchronize=' + ds.options.synchronize);
  record('TLS verification enabled',
    !!ds.options.ssl && ds.options.ssl.rejectUnauthorized === true,
    'rejectUnauthorized=' + (ds.options.ssl && ds.options.ssl.rejectUnauthorized));

  const protectedPaths = [
    '/api/v1/parties', '/api/v1/ledger', '/bills', '/inventory',
    '/reports/cashflow', '/uploads/receipts/22222222-2222-4222-8222-222222222222',
  ];
  for (const p of protectedPaths) {
    const res = await request(http).get(p);
    record('GET ' + p + ' refuses anonymous', res.status === 401, 'status ' + res.status);
  }

  await app.close();
}

async function localPass(request) {
  console.log('\nLOCAL-FULL (authenticated read + write against a real database)\n');
  const app = await boot(LOCAL_URL, 'development');
  const http = app.getHttpServer();
  const db = app.get(require('typeorm').DataSource);

  for (const t of TABLES) await db.query('DELETE FROM ' + t);

  const [user] = await db.query(
    'INSERT INTO users (phone_number, normalized_phone, name, "sessionVersion")' +
    " VALUES ($1,$2,'Smoke User',1) RETURNING id, \"sessionVersion\"",
    ['+919000000001', '9000000001'],
  );
  const [business] = await db.query(
    "INSERT INTO businesses (owner_id, name, type) VALUES ($1,'Smoke Shop','Retail') RETURNING id",
    [user.id],
  );

  // Required before any authenticated call: the guard resolves role from here.
  await db.query(
    "INSERT INTO business_members (business_id, user_id, role) VALUES ($1,$2,'OWNER')",
    [business.id, user.id],
  );

  const token = await app.get(require('@nestjs/jwt').JwtService).signAsync({
    sub: user.id, phone: '+919000000001', businessId: business.id,
    role: 'OWNER', sessionVersion: user.sessionVersion,
  });
  const auth = (req) => req.set('Authorization', 'Bearer ' + token);

  const partyId = '77777777-7777-4777-8777-777777777771';
  const entryId = '88888888-8888-4888-8888-888888888881';
  let r;

  r = await auth(request(http).post('/api/v1/parties')).send({
    id: partyId, name: 'Smoke Customer', phone: '+919876500001', type: 'CUSTOMER',
  });
  record('POST /api/v1/parties', r.status === 201, 'status ' + r.status);

  r = await auth(request(http).get('/api/v1/parties'));
  record('GET /api/v1/parties', r.status === 200 && r.body.some((p) => p.id === partyId));

  r = await auth(request(http).post('/api/v1/ledger')).send({
    id: entryId, partyId, amount: 500, type: 'GAVE', note: 'smoke',
  });
  record('POST /api/v1/ledger', r.status === 201, 'status ' + r.status);

  r = await auth(request(http).get('/api/v1/parties/' + partyId));
  record('balance applied', Number(r.body.currentBalance) === 500,
    'balance ' + r.body.currentBalance);

  await auth(request(http).post('/api/v1/ledger')).send({
    id: entryId, partyId, amount: 500, type: 'GAVE', note: 'smoke',
  });
  r = await auth(request(http).get('/api/v1/parties/' + partyId));
  record('replay does not double-count', Number(r.body.currentBalance) === 500,
    'balance ' + r.body.currentBalance);

  r = await auth(request(http).patch('/api/v1/ledger/' + entryId)).send({ amount: 800 });
  record('PATCH /api/v1/ledger/:id', r.status === 200, 'status ' + r.status);

  r = await auth(request(http).get('/api/v1/parties/' + partyId));
  record('edit adjusts by the difference', Number(r.body.currentBalance) === 800,
    'balance ' + r.body.currentBalance);

  const reads = [
    ['cashbook feed', '/api/v1/ledger'], ['reports', '/reports/cashflow'],
    ['inventory', '/inventory'], ['bills', '/bills'],
  ];
  for (const pair of reads) {
    r = await auth(request(http).get(pair[1]));
    record('GET ' + pair[1] + ' (' + pair[0] + ')', r.status === 200, 'status ' + r.status);
  }

  r = await auth(request(http).post('/inventory')).send({ name: 'Smoke Item', sellingPrice: 10 });
  record('POST /inventory accepts valid', r.status === 201, 'status ' + r.status);

  r = await auth(request(http).post('/inventory')).send({ name: 'x', businessId: 'other' });
  record('POST /inventory rejects tenancy override', r.status === 400, 'status ' + r.status);

  // Regression: balance_history references the entry ON DELETE NO ACTION, so
  // this returned 500 until those rows were detached first.
  r = await auth(request(http).delete('/api/v1/ledger/' + entryId));
  record('DELETE /api/v1/ledger/:id', r.status === 204, 'status ' + r.status);

  r = await auth(request(http).get('/api/v1/parties/' + partyId));
  record('delete reverses the balance', Number(r.body.currentBalance) === 0,
    'balance ' + r.body.currentBalance);

  // Rebuild activity so the party delete has dependents to cascade through.
  await auth(request(http).post('/api/v1/ledger')).send({
    partyId, amount: 250, type: 'GOT', note: 'second',
  });
  r = await auth(request(http).delete('/api/v1/parties/' + partyId));
  record('DELETE /api/v1/parties/:id with dependents', r.status === 204,
    'status ' + r.status);

  const orphan = await db.query(
    'SELECT count(*)::int n FROM ledger_entries WHERE party_id = $1', [partyId]);
  record('party delete leaves no orphaned entries', orphan[0].n === 0,
    orphan[0].n + ' rows');

  // ── staff scoping ───────────────────────────────────────────────────
  r = await auth(request(http).post('/users/staff')).send({
    phoneNumber: '+919888800001', name: 'Smoke Cashier', role: 'CASHIER',
  });
  record('POST /users/staff', r.status === 201, 'status ' + r.status);
  const membershipId = r.body.membershipId;

  r = await auth(request(http).get('/users/staff'));
  record('GET /users/staff lists owner + new cashier',
    r.status === 200 && r.body.length === 2,
    'status ' + r.status + ', ' + (r.body || []).length + ' members');

  // A second business with its own member must not appear in that listing.
  const otherUser = await db.query(
    "INSERT INTO users (phone_number, normalized_phone, name, \"sessionVersion\")" +
    " VALUES ('+919777700001','9777700001','Other Owner',1) RETURNING id");
  const otherBiz = await db.query(
    "INSERT INTO businesses (owner_id, name, type) VALUES ($1,'Other Shop','Retail') RETURNING id",
    [otherUser[0].id]);
  await db.query(
    "INSERT INTO business_members (business_id, user_id, role) VALUES ($1,$2,'CASHIER')",
    [otherBiz[0].id, otherUser[0].id]);

  r = await auth(request(http).get('/users/staff'));
  const leaked = (r.body || []).some((m) => m.phoneNumber === '+919777700001');
  record("another business's staff do not leak", !leaked,
    leaked ? 'LEAKED' : 'scoped correctly');

  r = await auth(request(http).delete('/users/staff/' + membershipId));
  record('DELETE /users/staff/:id', r.status === 204, 'status ' + r.status);

  const survivor = await db.query(
    "SELECT count(*)::int n FROM users WHERE phone_number = '+919888800001'");
  record('removing a member keeps their user account', survivor[0].n === 1,
    survivor[0].n + ' user rows');


  const audits = await db.query(
    'SELECT count(*)::int n FROM audit_logs WHERE "entityName" = \'LedgerEntry\'');
  record('audit subscriber wrote rows against a real database', audits[0].n > 0,
    audits[0].n + ' rows');

  for (const t of TABLES) await db.query('DELETE FROM ' + t);
  await app.close();
}

async function main() {
  const request = require('supertest');

  if (process.env.SMOKE_PROD === '1') await prodPass(request);
  else console.log('\nPROD-CONFIG skipped (set SMOKE_PROD=1 to include it)');

  await localPass(request);

  const failed = results.filter((x) => !x.ok);
  console.log('\nRESULT: ' + (results.length - failed.length) + '/' + results.length + ' passed');
  if (failed.length) {
    failed.forEach((f) => console.log('  FAILED: ' + f.name));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('\nSMOKE ERROR:', e.message);
  process.exit(1);
});
