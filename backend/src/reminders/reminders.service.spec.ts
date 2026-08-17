import { Test, TestingModule } from '@nestjs/testing';
import { getRepositoryToken } from '@nestjs/typeorm';
import { RemindersService } from './reminders.service';
import { SmsService } from '../notifications/sms/sms.service';
import { Party } from '../party/party.entity';

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
        {
          provide: getRepositoryToken(Party),
          useValue: { findOneBy: jest.fn().mockResolvedValue(null) },
        },
      ],
    }).compile();

    service = module.get<RemindersService>(RemindersService);
  });

  it('should be defined', () => {
    expect(service).toBeDefined();
  });
});
