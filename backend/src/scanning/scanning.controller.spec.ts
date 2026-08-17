import { INestApplication } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { ScanningController } from './scanning.controller';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { ScanningService } from './scanning.service';

describe('ScanningController', () => {
  let controller: ScanningController;

  beforeEach(async () => {
    const builder = Test.createTestingModule({
      controllers: [ScanningController],
      providers: [{ provide: ScanningService, useValue: {} }],
    });

    const module: TestingModule = await builder
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<ScanningController>(ScanningController);
  });

  it('should be defined', () => {
    expect(controller).toBeDefined();
  });
});

/**
 * POST /api/v1/scanning/extract rate limiting, over real HTTP.
 *
 * Each call reaches the paid Gemini API, so the route carries its own
 * @Throttle(10/min) tighter than the 120/min global default. This app is
 * built fresh with its own ThrottlerModule/APP_GUARD — isolated from any
 * other spec's request count — so the 11th request in one minute is the
 * first one guaranteed to exceed the limit.
 */
describe('POST /api/v1/scanning/extract rate limiting', () => {
  let app: INestApplication;
  const LIMIT = 10;
  const extractLedgerData = jest.fn().mockResolvedValue({ records: [] });

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      imports: [
        ThrottlerModule.forRoot([
          { name: 'default', ttl: 60_000, limit: LIMIT },
        ]),
      ],
      controllers: [ScanningController],
      providers: [
        { provide: ScanningService, useValue: { extractLedgerData } },
        { provide: APP_GUARD, useClass: ThrottlerGuard },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
      .compile();

    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('throttles repeated requests past the per-IP limit', async () => {
    const statuses: number[] = [];

    for (let i = 0; i < LIMIT + 1; i++) {
      const res = await request(app.getHttpServer())
        .post('/api/v1/scanning/extract')
        .attach('image', Buffer.from('fake-image-bytes'), 'sheet.jpg');
      statuses.push(res.status);
    }

    expect(statuses).toContain(429);
    expect(statuses.filter((s) => s === 200).length).toBeLessThanOrEqual(LIMIT);
  });
});

/**
 * File presence and size, over real HTTP — this is what actually enforces
 * the 10MB cap, since the limit lives in Multer's own `limits` option on the
 * interceptor rather than in application code the service layer could be
 * unit-tested against directly.
 */
describe('POST /api/v1/scanning/extract — upload validation', () => {
  let app: INestApplication;
  const extractLedgerData = jest.fn().mockResolvedValue({
    date: '2026-08-15',
    totalCollected: 500,
    records: [{ customerName: 'Rahul Traders', amount: 500 }],
  });

  beforeAll(async () => {
    const moduleRef: TestingModule = await Test.createTestingModule({
      controllers: [ScanningController],
      providers: [
        { provide: ScanningService, useValue: { extractLedgerData } },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
      .compile();

    app = moduleRef.createNestApplication();
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(() => {
    extractLedgerData.mockClear();
  });

  it('rejects a request with no image attached', async () => {
    const res = await request(app.getHttpServer()).post(
      '/api/v1/scanning/extract',
    );

    expect(res.status).toBe(400);
    expect(extractLedgerData).not.toHaveBeenCalled();
  });

  it('rejects an image over the 10MB limit before it reaches the service', async () => {
    const oversized = Buffer.alloc(11 * 1024 * 1024, 1);

    const res = await request(app.getHttpServer())
      .post('/api/v1/scanning/extract')
      .attach('image', oversized, 'huge.jpg');

    expect(res.status).toBe(413);
    expect(extractLedgerData).not.toHaveBeenCalled();
  });

  it('an authenticated request with a normal image reaches the service and returns its result', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/v1/scanning/extract')
      .attach('image', Buffer.from('fake-jpeg-bytes'), {
        filename: 'sheet.jpg',
        contentType: 'image/jpeg',
      });

    expect(res.status).toBe(201);
    expect(res.body.records).toHaveLength(1);
    expect(extractLedgerData).toHaveBeenCalledWith(
      expect.any(Buffer),
      'image/jpeg',
    );
  });

  it('rejects an unauthenticated request before it ever reaches the service', async () => {
    const unauthedModule: TestingModule = await Test.createTestingModule({
      controllers: [ScanningController],
      providers: [
        { provide: ScanningService, useValue: { extractLedgerData } },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => false })
      .compile();
    const unauthedApp = unauthedModule.createNestApplication();
    await unauthedApp.init();

    const res = await request(unauthedApp.getHttpServer())
      .post('/api/v1/scanning/extract')
      .attach('image', Buffer.from('fake-jpeg-bytes'), 'sheet.jpg');

    expect(res.status).toBe(403);
    expect(extractLedgerData).not.toHaveBeenCalled();

    await unauthedApp.close();
  });
});
