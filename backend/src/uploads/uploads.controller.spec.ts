import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { BusinessMember } from '../business/business-member.entity';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { mkdirSync, writeFileSync, rmSync } from 'fs';
import { join, sep } from 'path';
import { UploadsController } from './uploads.controller';
import { UploadsService } from './uploads.service';
import { Receipt } from './receipt.entity';
import { RECEIPTS_DIR, UPLOADS_ROOT } from './uploads.config';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { User } from '../users/user.entity';

/**
 * Receipts are financial documents.
 *
 * Two faults existed. The upload endpoint had no guard at all, so anyone able
 * to reach the port could write files to disk. And the files were exposed by an
 * unauthenticated static handler on /uploads/**, so any URL holder could read
 * another business's documents — the guard on the write path did nothing for
 * the read path.
 *
 * Uses the real JwtAuthGuard so the token checks are genuinely exercised; only
 * the repositories are stubbed.
 */
describe('UploadsController', () => {
  let membershipRole: any = 'OWNER';
  let app: INestApplication;
  let jwt: JwtService;

  const dbUser = { id: 'user-1', sessionVersion: 4 };
  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';

  const OWNED_ID = '22222222-2222-4222-8222-222222222222';
  const ORPHAN_ID = '33333333-3333-4333-8333-333333333333';
  const MISSING_ID = '44444444-4444-4444-8444-444444444444';

  const OWNED_FILE = 'receipt-test-owned.png';
  const ORPHAN_FILE = 'receipt-test-orphan.png';
  const FILE_BODY = 'pretend-png-bytes';

  // Rows the stubbed repository serves.
  /** Rows the quota check believes the business already holds. */
  let receiptCount = 0;

  const receipts: Receipt[] = [
    Object.assign(new Receipt(), {
      id: OWNED_ID,
      businessId: BUSINESS_A,
      uploadedBy: dbUser.id,
      filename: OWNED_FILE,
      mimeType: 'image/png',
      sizeBytes: FILE_BODY.length,
    }),
    // Row exists, but the file is gone — the ephemeral-disk case.
    Object.assign(new Receipt(), {
      id: ORPHAN_ID,
      businessId: BUSINESS_A,
      uploadedBy: dbUser.id,
      filename: ORPHAN_FILE,
      mimeType: 'image/png',
      sizeBytes: 10,
    }),
  ];

  beforeAll(async () => {
    mkdirSync(RECEIPTS_DIR, { recursive: true });
    writeFileSync(join(RECEIPTS_DIR, OWNED_FILE), FILE_BODY);

    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [UploadsController],
      providers: [
        {
          // JwtAuthGuard and AuthService both resolve the caller's role from
          // their membership now, so every spec constructing them needs one.
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            findOneBy: jest.fn(async () => ({ id: 'm-1', role: membershipRole })),
            find: jest.fn(async () => [
              { id: 'm-1', businessId: 'biz-1', role: membershipRole },
            ]),
            create: (o: any) => o,
            save: jest.fn(async (m: any) => m),
          },
        },
        UploadsService,
        {
          provide: getRepositoryToken(Receipt),
          useValue: {
            create: (o: any) => Object.assign(new Receipt(), o),
            // Backs the per-business quota check.
            count: jest.fn(async () => receiptCount),
            save: jest.fn(async (r: any) => ({ ...r, id: 'new-receipt-id' })),
            // Models a real repository: matches on whichever keys the query
            // actually carries. Hardcoding id AND businessId here would make
            // the cross-business tests pass even if the service stopped
            // scoping, since the absent key would simply never match.
            findOne: jest.fn(async ({ where }: any) =>
              receipts.find((r) =>
                Object.entries(where).every(
                  ([key, value]) => (r as any)[key] === value,
                ),
              ) ?? null,
            ),
          },
        },
        {
          provide: getRepositoryToken(User),
          useValue: {
            findOneBy: jest.fn(async ({ id }) => (id === dbUser.id ? dbUser : null)),
          },
        },
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => {
    await app.close();
    rmSync(join(RECEIPTS_DIR, OWNED_FILE), { force: true });
  });

  const token = (businessId: string, over: Record<string, any> = {}) =>
    jwt.sign({
      sub: dbUser.id,
      businessId,
      role: 'OWNER',
      sessionVersion: dbUser.sessionVersion,
      ...over,
    });

  // ── unauthorized access ────────────────────────────────────────────────
  describe('unauthorized access', () => {
    it('refuses a read with no token', async () => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .expect(401);
    });

    it('refuses a read with a token signed by the wrong secret', async () => {
      const forged = new JwtService({ secret: 'not-the-secret' }).sign({
        sub: dbUser.id,
        businessId: BUSINESS_A,
        sessionVersion: dbUser.sessionVersion,
      });

      await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${forged}`)
        .expect(401);
    });

    it('refuses a read on a stale session', async () => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A, { sessionVersion: 1 })}`)
        .expect(401);
    });

    it('refuses an upload with no token', async () => {
      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .attach('file', Buffer.from('x'), 'receipt.png')
        .expect(401);
    });
  });

  // ── cross-business access ──────────────────────────────────────────────
  describe('cross-business access', () => {
    it('does not serve a receipt belonging to another business', async () => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`)
        .expect(404);
    });

    it('reports it identically to a receipt that never existed', async () => {
      const foreign = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`);

      const absent = await request(app.getHttpServer())
        .get(`/uploads/receipts/${MISSING_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`);

      // 403 here would confirm the id exists, making this an existence oracle
      // across tenants. The two responses must be indistinguishable.
      expect(foreign.status).toBe(absent.status);
      expect(foreign.body.message).toBe(absent.body.message);
    });

    it('scopes the lookup by business in the query itself', async () => {
      const repo = app.get(getRepositoryToken(Receipt));
      repo.findOne.mockClear();

      await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`);

      // The filter must be in the query, not applied after the fact.
      expect(repo.findOne).toHaveBeenCalledWith({
        where: { id: OWNED_ID, businessId: BUSINESS_B },
      });
    });

    it('leaks no file content to the other business', async () => {
      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`);

      expect(res.text ?? '').not.toContain(FILE_BODY);
    });
  });

  // ── valid owner access ─────────────────────────────────────────────────
  describe('valid owner access', () => {
    it('serves the receipt to the owning business', async () => {
      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        // image/png is not a text type, so ask superagent for the raw bytes.
        .responseType('blob')
        .expect(200);

      expect(res.headers['content-type']).toContain('image/png');
      expect(Buffer.from(res.body).toString()).toBe(FILE_BODY);
    });

    it('marks the response private so shared caches do not retain it', async () => {
      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(200);

      expect(res.headers['cache-control']).toContain('no-store');
      expect(res.headers['cache-control']).toContain('private');
    });
  });

  // ── nonexistent receipts ───────────────────────────────────────────────
  describe('nonexistent receipts', () => {
    it('404s for an id that does not exist', async () => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${MISSING_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(404);
    });

    it('404s when the row survives but the file is gone', async () => {
      // Happens on ephemeral hosting: a redeploy wipes the disk, the database
      // keeps the row.
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${ORPHAN_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(404);
    });

    it.each([
      ['not a uuid', 'some-filename.png'],
      ['traversal attempt', '..%2F..%2F..%2Fetc%2Fpasswd'],
      ['on-disk filename', OWNED_FILE],
    ])('rejects %s with 400 rather than touching the filesystem', async (_label, id) => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${id}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(400);
    });
  });

  // ── the static handler is gone ─────────────────────────────────────────
  describe('the unauthenticated static route no longer exists', () => {
    it.each([
      `/uploads/receipts/${OWNED_FILE}`,
      `/uploads/${OWNED_FILE}`,
    ])('does not serve %s', async (path) => {
      const res = await request(app.getHttpServer()).get(path);

      expect(res.status).not.toBe(200);
      expect(res.text ?? '').not.toContain(FILE_BODY);
    });
  });

  // ── upload records ownership ───────────────────────────────────────────
  describe('upload', () => {
    it('records the receipt against the caller\'s business and returns its id', async () => {
      const res = await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', Buffer.from(FILE_BODY), 'receipt.png')
        .expect(201);

      expect(res.body.url).toBe('/uploads/receipts/new-receipt-id');

      const repo = app.get(getRepositoryToken(Receipt));
      expect(repo.save).toHaveBeenCalledWith(
        expect.objectContaining({ businessId: BUSINESS_A, uploadedBy: dbUser.id }),
      );

      // Clean up whatever multer wrote.
      const saved = repo.save.mock.calls.at(-1)[0];
      rmSync(join(RECEIPTS_DIR, saved.filename), { force: true });
    });

    it('refuses an upload once the business hits its receipt ceiling', async () => {
      receiptCount = 2000;

      const res = await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', Buffer.from(FILE_BODY), 'receipt.png');

      // Without a quota one tenant can fill the shared disk for everyone.
      expect(res.status).toBe(403);
      receiptCount = 0;
    });

    it('still rejects a disallowed file type', async () => {
      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', Buffer.from('#!/bin/sh'), 'payload.sh')
        .expect(400);
    });
  });

  // ── write and serve paths still agree ──────────────────────────────────
  describe('upload directory', () => {
    it('writes receipts underneath the directory that is served', () => {
      expect(RECEIPTS_DIR.startsWith(UPLOADS_ROOT)).toBe(true);
    });

    it('resolves the served root from the working directory, not dist', () => {
      expect(UPLOADS_ROOT).toBe(join(process.cwd(), 'uploads'));
      expect(UPLOADS_ROOT).not.toContain(`${sep}dist${sep}`);
    });
  });
});
