import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User, UserRole } from './user.entity';
import { BusinessMember } from '../business/business-member.entity';

/** A staff member as returned to the client. Never exposes OTP or session state. */
export interface StaffMember {
  membershipId: string;
  userId: string;
  name: string | null;
  phoneNumber: string;
  role: UserRole;
  addedAt: Date;
}

@Injectable()
export class UsersService {
  constructor(
    @InjectRepository(User)
    private userRepository: Repository<User>,
    @InjectRepository(BusinessMember)
    private memberRepository: Repository<BusinessMember>,
  ) {}

  /** Normalises to the storage form AuthService uses, so lookups agree. */
  private normalizePhone(value: string): string {
    const digits = (value || '').replace(/\D/g, '');
    if (!digits) return '';
    if (digits.length === 10) return digits;
    if (digits.length === 11 && digits.startsWith('0'))
      return digits.substring(1);
    if (digits.length === 12 && digits.startsWith('91'))
      return digits.substring(2);
    if (digits.length === 13 && digits.startsWith('091'))
      return digits.substring(3);
    return digits;
  }

  /**
   * Staff of one business.
   *
   * This previously returned every MANAGER and CASHIER in the database — names
   * and phone numbers belonging to other businesses — to any owner who asked.
   */
  async getStaff(businessId: string): Promise<StaffMember[]> {
    const members = await this.memberRepository.find({
      where: { businessId },
      relations: { user: true },
      order: { createdAt: 'ASC' },
    });

    return members.map((m) => ({
      membershipId: m.id,
      userId: m.userId,
      name: m.user?.name ?? null,
      phoneNumber: m.user?.phone_number ?? '',
      role: m.role,
      addedAt: m.createdAt,
    }));
  }

  /**
   * Adds a member to the caller's business.
   *
   * A user may already exist — they might run their own shop, or be staff
   * elsewhere — so the account is reused and only the membership is new. Adding
   * someone must never mint a second account for a phone number that already
   * has one, or that person could no longer log in unambiguously.
   */
  async addStaff(
    businessId: string,
    phoneNumber: string,
    name: string,
    role: UserRole,
  ): Promise<StaffMember> {
    if (role === UserRole.OWNER) {
      // Ownership transfers are a separate, deliberate operation.
      throw new BadRequestException('Cannot add another owner');
    }

    const normalized = this.normalizePhone(phoneNumber);
    if (!normalized) {
      throw new BadRequestException('A valid phone number is required');
    }

    let user = await this.userRepository.findOneBy({
      normalized_phone: normalized,
    });
    if (!user) {
      user = await this.userRepository.save(
        this.userRepository.create({
          phone_number: phoneNumber,
          normalized_phone: normalized,
          name,
          // The global column is legacy; role now lives on the membership.
          role,
        }),
      );
    }

    const existing = await this.memberRepository.findOneBy({
      businessId,
      userId: user.id,
    });
    if (existing) {
      throw new BadRequestException(
        'This person is already a member of this business',
      );
    }

    const member = await this.memberRepository.save(
      this.memberRepository.create({ businessId, userId: user.id, role }),
    );

    return {
      membershipId: member.id,
      userId: user.id,
      name: user.name ?? name,
      phoneNumber: user.phone_number,
      role: member.role,
      addedAt: member.createdAt,
    };
  }

  /**
   * Removes someone from this business.
   *
   * Deletes the membership, never the user: the account may belong to other
   * businesses, and removing a cashier from one shop must not destroy their
   * access to another. An owner cannot be removed this way, so a business
   * cannot be left without an administrator.
   */
  async removeStaff(
    businessId: string,
    membershipId: string,
    requestingUserId: string,
  ): Promise<void> {
    const member = await this.memberRepository.findOneBy({
      id: membershipId,
      businessId,
    });

    if (!member) {
      // Not found rather than forbidden: a membership in another business must
      // not be confirmed to exist.
      throw new NotFoundException('Staff member not found in this business');
    }

    if (member.role === UserRole.OWNER) {
      throw new BadRequestException('Cannot remove an owner');
    }

    if (member.userId === requestingUserId) {
      throw new ForbiddenException('You cannot remove yourself');
    }

    await this.memberRepository.remove(member);
  }

  /** The caller's role in a business, or null when they are not a member. */
  async findRole(businessId: string, userId: string): Promise<UserRole | null> {
    const member = await this.memberRepository.findOneBy({
      businessId,
      userId,
    });
    return member?.role ?? null;
  }

  async updateMe(userId: string, name?: string): Promise<User> {
    const user = await this.userRepository.findOneBy({ id: userId });
    if (!user) throw new NotFoundException('User not found');

    if (name !== undefined) user.name = name;
    return this.userRepository.save(user);
  }
}
