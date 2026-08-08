import { Test, TestingModule } from '@nestjs/testing';
import { UsersService } from './users.service';
import { getRepositoryToken } from '@nestjs/typeorm';
import { BusinessMember } from '../business/business-member.entity';
import { User } from './user.entity';

describe('UsersService', () => {
  let membershipRole: any = 'OWNER';
  let service: UsersService;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      providers: [
        {
          // JwtAuthGuard and AuthService both resolve the caller's role from
          // their membership now, so every spec constructing them needs one.
          provide: getRepositoryToken(BusinessMember),
          useValue: {
            findOneBy: jest.fn(async () => ({ id: 'm-1', role: membershipRole })),
            find: jest.fn(async () => [
              { id: 'm-1', businessId: 'biz-1', role: membershipRole },
            ]),
            create: (o: any) => o,
            save: jest.fn(async (m: any) => m),
          },
        },
        UsersService,
        { provide: getRepositoryToken(User), useValue: {} },
      ],
    }).compile();

    service = module.get<UsersService>(UsersService);
  });

  it('should be defined', () => {
    expect(service).toBeDefined();
  });
});
