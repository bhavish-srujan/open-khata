import { UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { JwtAuthGuard } from './jwt-auth.guard';

/**
 * sessionVersion invalidation.
 *
 * AuthService.verifyOtp() bumps user.sessionVersion on every successful
 * login and bakes the new value into the issued JWT. This guard is what
 * actually enforces that: a token minted before the bump must stop working
 * the moment a newer one is issued, even though the old token has not
 * expired.
 */
describe('JwtAuthGuard — sessionVersion invalidation', () => {
  const makeContext = (headers: Record<string, string>) => {
    const request: any = { headers };
    return {
      switchToHttp: () => ({ getRequest: () => request }),
    } as any;
  };

  const buildGuard = (user: any, memberships: any[] = []) => {
    const jwtService = { verifyAsync: jest.fn() } as unknown as JwtService;
    const userRepository = {
      findOneBy: jest.fn().mockResolvedValue(user),
    } as any;
    const memberRepository = {
      findOneBy: jest.fn(
        async (q: any) =>
          memberships.find((m) => m.businessId === q.businessId) ?? null,
      ),
    } as any;
    const guard = new JwtAuthGuard(
      jwtService,
      userRepository,
      memberRepository,
    );
    return { guard, jwtService };
  };

  it('rejects a token whose sessionVersion no longer matches the user row', async () => {
    const user = { id: 'user-1', sessionVersion: 2 };
    const { guard, jwtService } = buildGuard(user);
    (jwtService.verifyAsync as jest.Mock).mockResolvedValue({
      sub: 'user-1',
      sessionVersion: 1, // stale: issued before the last login bumped it to 2
    });

    await expect(
      guard.canActivate(makeContext({ authorization: 'Bearer old-token' })),
    ).rejects.toBeInstanceOf(UnauthorizedException);
  });

  it('accepts a token whose sessionVersion matches the current user row', async () => {
    const user = { id: 'user-1', sessionVersion: 2 };
    const { guard, jwtService } = buildGuard(user);
    (jwtService.verifyAsync as jest.Mock).mockResolvedValue({
      sub: 'user-1',
      sessionVersion: 2,
    });

    await expect(
      guard.canActivate(makeContext({ authorization: 'Bearer current-token' })),
    ).resolves.toBe(true);
  });
});
