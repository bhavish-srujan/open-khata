import { Test, TestingModule } from '@nestjs/testing';
import { AuthService } from './auth.service';
import { getRepositoryToken } from '@nestjs/typeorm';
import { User } from '../users/user.entity';
import { Business } from '../business/business.entity';
import { JwtService } from '@nestjs/jwt';
import { ConfigService } from '@nestjs/config';
import { HttpService } from '@nestjs/axios';

describe('AuthService', () => {
  let service: AuthService;
  const users = [] as any[];

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      providers: [
        AuthService,
        { provide: getRepositoryToken(User), useValue: {
          findOneBy: jest.fn((q) => users.find(u => Object.keys(q).every(k => u[k] === q[k]))),
          find: jest.fn(() => users),
          create: jest.fn((obj) => obj),
          save: jest.fn((u) => { if (!u.id) { u.id = 'id-' + Math.random(); users.push(u); } return Promise.resolve(u); }),
        } },
        { provide: getRepositoryToken(Business), useValue: { findOneBy: jest.fn(), create: jest.fn(), save: jest.fn() } },
        { provide: JwtService, useValue: { signAsync: jest.fn().mockResolvedValue('tok') } },
        { provide: ConfigService, useValue: { get: jest.fn() } },
        { provide: HttpService, useValue: {} },
      ],
    }).compile();

    service = module.get<AuthService>(AuthService);
  });

  it('findUserByPhone should match normalized phone', async () => {
    users.length = 0;
    users.push({ id: '1', phone_number: '+91-6366605032', normalized_phone: '6366605032', name: 'Srujan Traders' });

    const found = await service.findUserByPhone('6366605032');
    expect(found).toBeDefined();
    expect(found.name).toBe('Srujan Traders');
  });

  it('findUserByPhone should reuse same account for different phone formats', async () => {
    users.length = 0;
    users.push({ id: '1', phone_number: '6366605032', normalized_phone: '6366605032', name: 'Srujan Traders' });

    const found = await service.findUserByPhone('+916366605032');
    expect(found).toBeDefined();
    expect(found.id).toBe('1');
    expect(found.normalized_phone).toBe('6366605032');
  });

  it('logout should increment sessionVersion', async () => {
    users.length = 0;
    users.push({ id: 'u1', phone_number: '6366605032', sessionVersion: 1 });
    await service.logout('u1');
    const u = users.find(x => x.id === 'u1');
    expect(u.sessionVersion).toBe(2);
  });
});
