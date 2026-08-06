import { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Tracks failed OTP verifications per user.
 *
 * Verification previously had no attempt counter and the project had no rate
 * limiting, so a six-digit code with a ten-minute window could be enumerated
 * outright. Idempotent, in keeping with the rest of the set.
 */
export class AddOtpAttemptsToUsers1786200000000 implements MigrationInterface {
  name = 'AddOtpAttemptsToUsers1786200000000';

  public async up(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "otpAttempts" integer NOT NULL DEFAULT 0`,
    );
  }

  public async down(queryRunner: QueryRunner): Promise<void> {
    await queryRunner.query(
      `ALTER TABLE "users" DROP COLUMN IF EXISTS "otpAttempts"`,
    );
  }
}
