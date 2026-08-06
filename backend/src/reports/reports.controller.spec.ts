import { Test, TestingModule } from '@nestjs/testing';
import { ReportsController } from './reports.controller';
import { RolesGuard } from '../auth/roles.guard';
import { ReportsService } from './reports.service';
import { PdfService } from './pdf/pdf.service';

describe('ReportsController', () => {
  let controller: ReportsController;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      controllers: [ReportsController],
      providers: [
        { provide: RolesGuard, useValue: { canActivate: () => true } },
        { provide: ReportsService, useValue: {} },
        { provide: PdfService, useValue: { generateBusinessStatement: jest.fn().mockReturnValue(Buffer.from('')) } },
      ],
    }).compile();

    controller = module.get<ReportsController>(ReportsController);
  });

  it('should be defined', () => {
    expect(controller).toBeDefined();
  });
});
