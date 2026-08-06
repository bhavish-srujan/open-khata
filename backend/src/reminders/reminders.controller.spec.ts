import { Test, TestingModule } from '@nestjs/testing';
import { RemindersController } from './reminders.controller';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { RemindersService } from './reminders.service';

describe('RemindersController', () => {
  let controller: RemindersController;

  beforeEach(async () => {
    const builder = Test.createTestingModule({
      controllers: [RemindersController],
      providers: [
        { provide: RemindersService, useValue: {} },
      ],
    });

    const module: TestingModule = await builder
      .overrideGuard(JwtAuthGuard).useValue({ canActivate: () => true })
      .compile();

    controller = module.get<RemindersController>(RemindersController);
  });

  it('should be defined', () => {
    expect(controller).toBeDefined();
  });
});
