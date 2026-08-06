import { Test, TestingModule } from '@nestjs/testing';
import { RemindersService } from './reminders.service';
import { SmsService } from '../notifications/sms/sms.service';

describe('RemindersService', () => {
  let service: RemindersService;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      providers: [
        RemindersService,
        {
          provide: SmsService,
          useValue: {
            isConfigured: jest.fn().mockReturnValue(false),
            sendSms: jest.fn().mockResolvedValue(true),
          },
        },
      ],
    }).compile();

    service = module.get<RemindersService>(RemindersService);
  });

  it('should be defined', () => {
    expect(service).toBeDefined();
  });
});
