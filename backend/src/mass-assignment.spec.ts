import { INestApplication, ValidationPipe } from '@nestjs/common';
import { getRepositoryToken } from '@nestjs/typeorm';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { BusinessController } from './business/business.controller';
import { BusinessService } from './business/business.service';
import { Business } from './business/business.entity';
import { InventoryController } from './inventory/inventory.controller';
import { InventoryService } from './inventory/inventory.service';
import { Item } from './inventory/item.entity';
import { JwtAuthGuard } from './auth/jwt-auth.guard';
import { RolesGuard } from './auth/roles.guard';

/**
 * Mass assignment on business and inventory updates.
 *
 * PUT /businesses/:id took `@Body() body: any` and applied it with
 * Object.assign, so a request could set ownerId and hand the business to
 * someone else. PUT /inventory/:id took `Partial<Item>` — a mapped type that
 * erases to Object, so validation was skipped entirely — and likewise
 * Object.assign'd, so businessId could move stock between tenants.
 *
 * Both layers are asserted: the DTO rejects the field at the boundary, and the
 * service ignores it even when handed directly.
 */
describe('mass assignment is not possible', () => {
  let app: INestApplication;
  let businessService: BusinessService;
  let inventoryService: InventoryService;

  const OWNER = 'owner-1';
  const BUSINESS_ID = '11111111-1111-4111-8111-111111111111';
  const OTHER_BUSINESS = '99999999-9999-4999-8999-999999999999';

  let businessRow: any;
  let itemRow: any;

  beforeEach(async () => {
    businessRow = Object.assign(new Business(), {
      id: BUSINESS_ID,
      ownerId: OWNER,
      name: 'Rajesh Kirana',
      type: 'Retail',
      gstNumber: '29ABCDE1234F1Z5',
    });

    itemRow = Object.assign(new Item(), {
      id: 'item-1',
      businessId: BUSINESS_ID,
      name: 'Rice 5kg',
      sellingPrice: 480,
      currentStock: 12,
    });

    const moduleRef = await Test.createTestingModule({
      controllers: [BusinessController, InventoryController],
      providers: [
        BusinessService,
        InventoryService,
        {
          provide: getRepositoryToken(Business),
          useValue: {
            findOne: jest.fn(async ({ where }: any) =>
              where.id === businessRow.id && where.ownerId === businessRow.ownerId
                ? businessRow
                : null,
            ),
            save: jest.fn(async (b: any) => b),
          },
        },
        {
          provide: getRepositoryToken(Item),
          useValue: {
            findOne: jest.fn(async ({ where }: any) =>
              where.id === itemRow.id && where.businessId === itemRow.businessId
                ? itemRow
                : null,
            ),
            create: (o: any) => Object.assign(new Item(), o),
            save: jest.fn(async (i: any) => i),
          },
        },
      ],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({
        canActivate: (c: any) => {
          c.switchToHttp().getRequest().user = {
            sub: OWNER,
            businessId: BUSINESS_ID,
            role: 'OWNER',
          };
          return true;
        },
      })
      .overrideGuard(RolesGuard)
      .useValue({ canActivate: () => true })
      .compile();

    businessService = moduleRef.get(BusinessService);
    inventoryService = moduleRef.get(InventoryService);

    app = moduleRef.createNestApplication();
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true }));
    await app.init();
  });

  afterEach(async () => {
    await app.close();
  });

  describe('PUT /businesses/:id', () => {
    it('applies a legitimate rename', async () => {
      await request(app.getHttpServer())
        .put(`/businesses/${BUSINESS_ID}`)
        .send({ name: 'Rajesh Superstore' })
        .expect(200);

      expect(businessRow.name).toBe('Rajesh Superstore');
    });

    it.each([
      ['ownerId', { name: 'x', ownerId: 'attacker-user-id' }],
      ['id', { name: 'x', id: 'forged-id' }],
      ['createdAt', { name: 'x', createdAt: '1999-01-01' }],
    ])('rejects an attempt to set %s', async (field, body) => {
      const res = await request(app.getHttpServer())
        .put(`/businesses/${BUSINESS_ID}`)
        .send(body);

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });

    it('leaves ownership untouched even when the service is called directly', async () => {
      await businessService.update(BUSINESS_ID, OWNER, {
        name: 'Renamed',
        ownerId: 'attacker-user-id',
        id: 'forged-id',
      } as any);

      expect(businessRow.ownerId).toBe(OWNER);
      expect(businessRow.id).toBe(BUSINESS_ID);
      expect(businessRow.name).toBe('Renamed');
    });
  });

  describe('PUT /inventory/:id', () => {
    it('applies a legitimate stock change', async () => {
      await request(app.getHttpServer())
        .put('/inventory/item-1')
        .send({ currentStock: 3 })
        .expect(200);

      expect(Number(itemRow.currentStock)).toBe(3);
    });

    it.each([
      ['businessId', { currentStock: 3, businessId: OTHER_BUSINESS }],
      ['id', { currentStock: 3, id: 'forged-id' }],
    ])('rejects an attempt to set %s', async (field, body) => {
      const res = await request(app.getHttpServer())
        .put('/inventory/item-1')
        .send(body);

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });

    it('does not move the item between businesses even called directly', async () => {
      await inventoryService.update(
        'item-1',
        { currentStock: 3, businessId: OTHER_BUSINESS, id: 'forged-id' } as any,
        BUSINESS_ID,
      );

      expect(itemRow.businessId).toBe(BUSINESS_ID);
      expect(itemRow.id).toBe('item-1');
      expect(Number(itemRow.currentStock)).toBe(3);
    });
  });

  describe('POST /inventory now validates, having previously accepted anything', () => {
    it('accepts a well-formed item', async () => {
      await request(app.getHttpServer())
        .post('/inventory')
        .send({ name: 'Atta 10kg', sellingPrice: 450, currentStock: 8 })
        .expect(201);
    });

    it.each([
      ['missing name', { sellingPrice: 10 }, 'name'],
      ['negative price', { name: 'x', sellingPrice: -5 }, 'sellingPrice'],
      ['over-precise price', { name: 'x', sellingPrice: 1.239 }, 'sellingPrice'],
      ['price as a string', { name: 'x', sellingPrice: '450' }, 'sellingPrice'],
      ['unknown field', { name: 'x', businessId: OTHER_BUSINESS }, 'businessId'],
    ])('rejects %s', async (_label, body, field) => {
      const res = await request(app.getHttpServer()).post('/inventory').send(body);

      expect(res.status).toBe(400);
      expect(JSON.stringify(res.body.message)).toContain(field);
    });

    it('always stamps the business from the token', async () => {
      const created = await inventoryService.create(
        { name: 'Atta 10kg' } as any,
        BUSINESS_ID,
      );

      expect(created.businessId).toBe(BUSINESS_ID);
    });
  });
});
