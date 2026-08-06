import { Test, TestingModule } from '@nestjs/testing';
import { InventoryController } from './inventory.controller';
import { RolesGuard } from '../auth/roles.guard';
import { InventoryService } from './inventory.service';

describe('InventoryController', () => {
  let controller: InventoryController;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      controllers: [InventoryController],
      providers: [
        { provide: RolesGuard, useValue: { canActivate: () => true } },
        { provide: InventoryService, useValue: {} },
      ],
    }).compile();

    controller = module.get<InventoryController>(InventoryController);
  });

  it('should be defined', () => {
    expect(controller).toBeDefined();
  });
});
