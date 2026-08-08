import {
  Injectable,
  CanActivate,
  ExecutionContext,
  UnauthorizedException,
} from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from '../users/user.entity';
import { BusinessMember } from '../business/business-member.entity';
import { setAuditUser } from '../audit-logs/audit-context';

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private jwtService: JwtService,
    @InjectRepository(User) private userRepository: Repository<User>,
    @InjectRepository(BusinessMember)
    private memberRepository: Repository<BusinessMember>,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest();
    const authHeader: string | undefined = request.headers['authorization'];

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      throw new UnauthorizedException('Missing or invalid Authorization header');
    }

    const token = authHeader.split(' ')[1];

    try {
      const payload = await this.jwtService.verifyAsync(token);
      
      const user = await this.userRepository.findOneBy({ id: payload.sub });
      if (!user || user.sessionVersion !== payload.sessionVersion) {
        throw new UnauthorizedException('Session expired. Logged in from another device.');
      }

      // Role comes from the membership, not from the token.
      //
      // A role baked into a 7-day JWT cannot be reduced or revoked without
      // waiting for it to expire: removing a cashier would leave their existing
      // token working. Resolving here means a membership change takes effect on
      // the very next request. It costs one indexed lookup on a guard that
      // already queries the user row.
      if (payload.businessId) {
        const membership = await this.memberRepository.findOneBy({
          businessId: payload.businessId,
          userId: payload.sub,
        });

        if (!membership) {
          throw new UnauthorizedException(
            'You no longer have access to this business.',
          );
        }

        payload.role = membership.role;
      }

      request['user'] = payload;
      // Attribution for AuditLogSubscriber, which has no access to the request.
      setAuditUser(payload.sub);
      return true;
    } catch (error) {
      if (error instanceof UnauthorizedException) {
        throw error;
      }
      throw new UnauthorizedException('Token is invalid or expired');
    }
  }
}
