import { Test, TestingModule } from '@nestjs/testing';
import { ScanningService } from './scanning.service';
import { ConfigService } from '@nestjs/config';

describe('ScanningService', () => {
  let service: ScanningService;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      providers: [
        ScanningService,
        { provide: ConfigService, useValue: { get: jest.fn().mockReturnValue('') } },
      ],
    }).compile();

    service = module.get<ScanningService>(ScanningService);
  });

  it('should be defined', () => {
    expect(service).toBeDefined();
  });
});
