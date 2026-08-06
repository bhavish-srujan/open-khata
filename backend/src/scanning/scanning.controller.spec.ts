import { Test, TestingModule } from '@nestjs/testing';
import { ScanningController } from './scanning.controller';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { ScanningService } from './scanning.service';

describe('ScanningController', () => {
  let controller: ScanningController;

  beforeEach(async () => {
    const builder = Test.createTestingModule({
      controllers: [ScanningController],
      providers: [
        { provide: ScanningService, useValue: {} },
      ],
    });

    const module: TestingModule = await builder
      .overrideGuard(JwtAuthGuard).useValue({ canActivate: () => true })
      .compile();

    controller = module.get<ScanningController>(ScanningController);
  });

  it('should be defined', () => {
    expect(controller).toBeDefined();
  });
});
