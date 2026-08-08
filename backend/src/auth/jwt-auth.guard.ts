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

  /**
   * Reads a header value, tolerating the array form Node uses for repeats.
   * A repeated header is rejected rather than guessed at.
   */
  private readHeader(request: any, name: string): string | undefined {
    const raw = request.headers?.[name];
    if (Array.isArray(raw)) return undefined;
    const value = typeof raw === 'string' ? raw.trim() : '';
    return value.length > 0 ? value : undefined;
  }

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
      // The active business may be switched per request via x-business-id.
      //
      // The token names one business, but a user can belong to several and the
      // app lets them switch without re-authenticating. The header is safe here
      // only because membership is verified below — an earlier version accepted
      // it unverified, which let a caller name any business they liked. It is
      // now exactly as trustworthy as the token's own claim, and no more.
      const requestedBusinessId =
        this.readHeader(request, 'x-business-id') ?? payload.businessId;

      if (requestedBusinessId) {
        const membership = await this.memberRepository.findOneBy({
          businessId: requestedBusinessId,
          userId: payload.sub,
        });

        if (!membership) {
          throw new UnauthorizedException(
            'You no longer have access to this business.',
          );
        }

        payload.businessId = requestedBusinessId;
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
