import { Test, TestingModule } from '@nestjs/testing';
import { ReportsController } from './reports.controller';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RolesGuard } from '../auth/roles.guard';
import { ReportsService } from './reports.service';
import { PdfService } from './pdf/pdf.service';

describe('ReportsController', () => {
  let controller: ReportsController;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      controllers: [ReportsController],
      providers: [
        { provide: ReportsService, useValue: {} },
        { provide: PdfService, useValue: { generateBusinessStatement: jest.fn().mockReturnValue(Buffer.from('')) } },
      ],
    })
      // Guards referenced by class in @UseGuards are instantiated as enhancers,
      // so they must be replaced with overrideGuard rather than a provider.
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
      .overrideGuard(RolesGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get<ReportsController>(ReportsController);
  });

  it('should be defined', () => {
    expect(controller).toBeDefined();
  });
});
