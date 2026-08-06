import { Injectable, NotFoundException, BadRequestException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User, UserRole } from './user.entity';

@Injectable()
export class UsersService {
  constructor(
    @InjectRepository(User)
    private userRepository: Repository<User>,
  ) {}

  async getAllUsers(): Promise<User[]> {
    return this.userRepository.find();
  }

  async getStaff(): Promise<User[]> {
    return this.userRepository.createQueryBuilder('user')
      .where('user.role IN (:...roles)', { roles: [UserRole.MANAGER, UserRole.CASHIER] })
      .getMany();
  }

  async addStaff(phoneNumber: string, name: string, role: UserRole): Promise<User> {
    if (role === UserRole.OWNER) {
      throw new BadRequestException('Cannot add another owner');
    }

    let user = await this.userRepository.findOneBy({ phone_number: phoneNumber });
    if (user) {
      throw new BadRequestException('User with this phone number already exists');
    }

    user = this.userRepository.create({
      phone_number: phoneNumber,
      name,
      role,
    });
    return this.userRepository.save(user);
  }

  async removeStaff(userId: string): Promise<void> {
    const user = await this.userRepository.findOneBy({ id: userId });
    if (!user) {
      throw new NotFoundException('Staff not found');
    }
    if (user.role === UserRole.OWNER) {
      throw new BadRequestException('Cannot remove an owner');
    }
    await this.userRepository.remove(user);
  }
}
