import { Test, TestingModule } from '@nestjs/testing';
import { ScanningService } from './scanning.service';

describe('ScanningService', () => {
  let service: ScanningService;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      providers: [ScanningService],
    }).compile();

    service = module.get<ScanningService>(ScanningService);
  });

  it('should be defined', () => {
    expect(service).toBeDefined();
  });
});
