import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { UploadsController } from './uploads.controller';
import { UploadsService } from './uploads.service';
import { Receipt } from './receipt.entity';
import { StorageService } from '../storage/storage.interface';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { User } from '../users/user.entity';
import { BusinessMember } from '../business/business-member.entity';

/**
 * Receipts are financial documents.
 *
 * Three faults existed. The upload endpoint had no guard, so anyone reaching
 * the port could write files. The files were separately exposed by an
 * unauthenticated static handler, so any URL holder could read another
 * business's documents. And they were written to a container filesystem that a
 * redeploy destroys.
 *
 * Uses the real JwtAuthGuard so token checks are genuinely exercised; the
 * repositories and the storage driver are stubbed. The driver itself is
 * verified against a real S3 API in s3-storage.integration.spec.ts.
 */
describe('UploadsController', () => {
  const membershipRole: any = 'OWNER';
  let app: INestApplication;
  let jwt: JwtService;

  const dbUser = { id: 'user-1', sessionVersion: 4 };
  const BUSINESS_A = '11111111-1111-4111-8111-111111111111';
  const BUSINESS_B = '99999999-9999-4999-8999-999999999999';

  const OWNED_ID = '22222222-2222-4222-8222-222222222222';
  const ORPHAN_ID = '33333333-3333-4333-8333-333333333333';
  const MISSING_ID = '44444444-4444-4444-8444-444444444444';

  const OWNED_KEY = `receipts/${BUSINESS_A}/owned.png`;
  const ORPHAN_KEY = `receipts/${BUSINESS_A}/orphan.png`;
  const FILE_BODY = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);
  const SIGNED_URL = 'https://bucket.example/signed?sig=abc';

  let receipts: Receipt[];
  let receiptCount = 0;
  let objects: Record<string, Buffer>;
  /** null mimics the local-disk driver, which cannot sign. */
  let signedUrl: string | null = SIGNED_URL;
  let putCalls: { key: string; contentType: string }[];

  const png = (extra = 'body') =>
    Buffer.concat([
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      Buffer.from(extra),
    ]);

  beforeEach(async () => {
    receiptCount = 0;
    signedUrl = SIGNED_URL;
    putCalls = [];
    objects = { [OWNED_KEY]: FILE_BODY };

    receipts = [
      Object.assign(new Receipt(), {
        id: OWNED_ID, businessId: BUSINESS_A, uploadedBy: dbUser.id,
        filename: OWNED_KEY, mimeType: 'image/png', sizeBytes: FILE_BODY.length,
      }),
      // Row exists, object does not: anything uploaded before object storage.
      Object.assign(new Receipt(), {
        id: ORPHAN_ID, businessId: BUSINESS_A, uploadedBy: dbUser.id,
        filename: ORPHAN_KEY, mimeType: 'image/png', sizeBytes: 10,
      }),
    ];

    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [UploadsController],
      providers: [
        UploadsService,
        {
          provide: StorageService,
          useValue: {
            put: jest.fn(async (key: string, body: Buffer, contentType: string) => {
              putCalls.push({ key, contentType });
              objects[key] = body;
              return { key, size: body.length, contentType };
            }),
            get: jest.fn(async (key: string) => objects[key]),
            exists: jest.fn(async (key: string) => key in objects),
            getSignedUrl: jest.fn(async () => signedUrl),
            delete: jest.fn(async () => undefined),
          },
        },
        {
          provide: getRepositoryToken(Receipt),
          useValue: {
            create: (o: any) => Object.assign(new Receipt(), o),
            count: jest.fn(async () => receiptCount),
            save: jest.fn(async (r: any) => ({ ...r, id: 'new-receipt-id' })),
            // Matches on whichever keys the query carries, like a real
            // repository: hardcoding them would let an unscoped query pass.
            findOne: jest.fn(async ({ where }: any) =>
              receipts.find((r) =>
                Object.entries(where).every(([k, v]) => (r as any)[k] === v),
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
        {
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            findOneBy: jest.fn(async () => ({ id: 'm-1', role: membershipRole })),
          },
        },
      ],
    }).compile();

    jwt = moduleRef.get(JwtService);
    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterEach(async () => {
    await app.close();
  });

  const token = (businessId: string, over: Record<string, any> = {}) =>
    jwt.sign({
      sub: dbUser.id, businessId, role: 'OWNER',
      sessionVersion: dbUser.sessionVersion, ...over,
    });

  describe('unauthorized access', () => {
    it('refuses a read with no token', async () => {
      await request(app.getHttpServer()).get(`/uploads/receipts/${OWNED_ID}`).expect(401);
    });

    it('refuses a read with a token signed by the wrong secret', async () => {
      const forged = new JwtService({ secret: 'not-the-secret' }).sign({
        sub: dbUser.id, businessId: BUSINESS_A, sessionVersion: dbUser.sessionVersion,
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
        .attach('file', png(), 'receipt.png')
        .expect(401);
    });
  });

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

      // 403 would confirm the id exists, making this a cross-tenant oracle.
      expect(foreign.status).toBe(absent.status);
      expect(foreign.body.message).toBe(absent.body.message);
    });

    it('scopes the lookup by business in the query itself', async () => {
      const repo = app.get(getRepositoryToken(Receipt));
      repo.findOne.mockClear();

      await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`);

      expect(repo.findOne).toHaveBeenCalledWith({
        where: { id: OWNED_ID, businessId: BUSINESS_B },
      });
    });

    it("never mints a signed URL for another business's receipt", async () => {
      const storage = app.get(StorageService) as any;

      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_B)}`);

      expect(res.status).toBe(404);
      // The signature must be minted only after ownership is established.
      expect(storage.getSignedUrl).not.toHaveBeenCalled();
    });

    it("stores each business's objects under its own key prefix", async () => {
      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', png(), 'a.png')
        .expect(201);

      expect(putCalls[0].key.startsWith(`receipts/${BUSINESS_A}/`)).toBe(true);
    });
  });

  describe('valid owner access', () => {
    it('redirects to a short-lived signed URL', async () => {
      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .redirects(0);

      expect(res.status).toBe(302);
      expect(res.headers.location).toBe(SIGNED_URL);
    });

    it('streams the bytes when the driver cannot sign', async () => {
      signedUrl = null; // local disk

      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .responseType('blob')
        .expect(200);

      expect(Buffer.from(res.body).equals(FILE_BODY)).toBe(true);
      // Never inline: content that sniffs as HTML must not run in this origin.
      expect(res.headers['content-disposition']).toContain('attachment');
      expect(res.headers['x-content-type-options']).toBe('nosniff');
    });

    it('marks the response private so shared caches do not retain it', async () => {
      const res = await request(app.getHttpServer())
        .get(`/uploads/receipts/${OWNED_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .redirects(0);

      expect(res.headers['cache-control']).toContain('no-store');
      expect(res.headers['cache-control']).toContain('private');
    });
  });

  describe('nonexistent receipts', () => {
    it('404s for an id that does not exist', async () => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${MISSING_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(404);
    });

    it('404s when the row survives but the object is gone', async () => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${ORPHAN_ID}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(404);
    });

    it.each([
      ['not a uuid', 'some-filename.png'],
      ['traversal attempt', '..%2F..%2F..%2Fetc%2Fpasswd'],
      ['a storage key', 'receipts%2Fx%2Fy.png'],
    ])('rejects %s with 400 before any lookup', async (_label, id) => {
      await request(app.getHttpServer())
        .get(`/uploads/receipts/${id}`)
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(400);
    });
  });

  describe('upload', () => {
    it("records the receipt against the caller's business", async () => {
      const res = await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', png(), 'receipt.png')
        .expect(201);

      expect(res.body.url).toBe('/uploads/receipts/new-receipt-id');

      const repo = app.get(getRepositoryToken(Receipt));
      expect(repo.save).toHaveBeenCalledWith(
        expect.objectContaining({ businessId: BUSINESS_A, uploadedBy: dbUser.id }),
      );
    });

    it('trusts the bytes, not the declared content type', async () => {
      // HTML masquerading as a PNG: the extension filter alone allowed this.
      const res = await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', Buffer.from('<html><script>alert(1)</script></html>'), {
          filename: 'evil.png',
          contentType: 'image/png',
        });

      expect(res.status).toBe(400);
    });

    it.each([
      ['jpeg', Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2]), 'image/jpeg'],
      ['pdf', Buffer.from('%PDF-1.7\nbody'), 'application/pdf'],
    ])('accepts a real %s and records its true type', async (_label, body, mime) => {
      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', body, 'file.bin')
        .expect(201);

      expect(putCalls.at(-1)!.contentType).toBe(mime);
    });

    it('names the object from the detected type, not the uploaded name', async () => {
      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', Buffer.from('%PDF-1.7'), 'invoice.png.exe')
        .expect(201);

      expect(putCalls.at(-1)!.key.endsWith('.pdf')).toBe(true);
    });

    it('refuses an upload once the business hits its ceiling', async () => {
      receiptCount = 2000;

      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .attach('file', png(), 'receipt.png')
        .expect(403);
    });

    it('rejects an authenticated request with no file', async () => {
      await request(app.getHttpServer())
        .post('/uploads/receipt')
        .set('Authorization', `Bearer ${token(BUSINESS_A)}`)
        .expect(400);
    });
  });
});
