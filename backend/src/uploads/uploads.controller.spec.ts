import { INestApplication } from '@nestjs/common';
import { JwtModule, JwtService } from '@nestjs/jwt';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { join, sep } from 'path';
import { UploadsController } from './uploads.controller';
import { RECEIPTS_DIR, UPLOADS_ROOT } from './uploads.config';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { User } from '../users/user.entity';

/**
 * Receipt uploads carry financial documents, but the controller had no guard at
 * all: anyone who could reach the port could write files to disk. Uses the real
 * JwtAuthGuard so these assertions exercise the actual token check.
 */
describe('UploadsController', () => {
  let app: INestApplication;
  let jwt: JwtService;

  const dbUser = { id: 'user-1', sessionVersion: 4 };

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [JwtModule.register({ secret: 'test-secret' })],
      controllers: [UploadsController],
      providers: [
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
  });

  const token = (over: Record<string, any> = {}) =>
    jwt.sign({
      sub: dbUser.id,
      businessId: 'biz-1',
      role: 'OWNER',
      sessionVersion: dbUser.sessionVersion,
      ...over,
    });

  it('rejects an upload with no token', async () => {
    await request(app.getHttpServer())
      .post('/uploads/receipt')
      .attach('file', Buffer.from('fake-image'), 'receipt.png')
      .expect(401);
  });

  it('rejects a token signed with the wrong secret', async () => {
    const forged = new JwtService({ secret: 'not-the-secret' }).sign({
      sub: dbUser.id,
      sessionVersion: dbUser.sessionVersion,
    });

    await request(app.getHttpServer())
      .post('/uploads/receipt')
      .set('Authorization', `Bearer ${forged}`)
      .attach('file', Buffer.from('fake-image'), 'receipt.png')
      .expect(401);
  });

  it('rejects a stale session', async () => {
    await request(app.getHttpServer())
      .post('/uploads/receipt')
      .set('Authorization', `Bearer ${token({ sessionVersion: 1 })}`)
      .attach('file', Buffer.from('fake-image'), 'receipt.png')
      .expect(401);
  });

  it('rejects a disallowed file type even when authenticated', async () => {
    await request(app.getHttpServer())
      .post('/uploads/receipt')
      .set('Authorization', `Bearer ${token()}`)
      .attach('file', Buffer.from('#!/bin/sh'), 'payload.sh')
      .expect(400);
  });

  it('rejects an authenticated request with no file', async () => {
    await request(app.getHttpServer())
      .post('/uploads/receipt')
      .set('Authorization', `Bearer ${token()}`)
      .expect(400);
  });

  /**
   * The write path and the serve path used to be independent strings that
   * disagreed: multer wrote to a cwd-relative ./uploads/receipts while static
   * serving resolved dist/uploads off __dirname, so uploads were never
   * reachable. They now derive from one constant.
   */
  describe('upload directory', () => {
    it('writes receipts underneath the directory that is served', () => {
      expect(RECEIPTS_DIR.startsWith(UPLOADS_ROOT)).toBe(true);
    });

    it('resolves the served root from the working directory, not dist', () => {
      expect(UPLOADS_ROOT).toBe(join(process.cwd(), 'uploads'));
      expect(UPLOADS_ROOT).not.toContain(`${sep}dist${sep}`);
    });

    it('serves the receipts directory at the URL the controller returns', () => {
      // Controller replies with /uploads/receipts/<filename>.
      const urlPath = RECEIPTS_DIR.slice(UPLOADS_ROOT.length).split(sep).join('/');
      expect(`/uploads${urlPath}`).toBe('/uploads/receipts');
    });
  });
});
