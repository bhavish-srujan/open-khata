import { plainToInstance } from 'class-transformer';
import { validate } from 'class-validator';
import { CreateLedgerEntryDto } from './dto/create-ledger-entry.dto';
import {
  isRealIsoDate,
  sanitizeExtraction,
} from '../scanning/scanning.service';

/**
 * The business date a user states, kept separate from the timestamp the app
 * records. Entering yesterday's sale today must land in yesterday's
 * collection while createdAt still says it was recorded today.
 */
describe('transactionDate — validation and AI sanitisation', () => {
  const base = {
    partyId: '33333333-3333-4333-8333-333333333333',
    amount: 500,
    type: 'GAVE' as const,
  };

  const errorsFor = async (payload: Record<string, unknown>) => {
    const dto = plainToInstance(CreateLedgerEntryDto, payload);
    const errors = await validate(dto, {
      whitelist: true,
      forbidNonWhitelisted: true,
    });
    return errors.map((e) => e.property);
  };

  // ─── DTO ────────────────────────────────────────────────────

  it('accepts a create with no transactionDate (older clients)', async () => {
    expect(await errorsFor(base)).toEqual([]);
  });

  it('accepts a valid ISO transactionDate', async () => {
    expect(
      await errorsFor({ ...base, transactionDate: '2026-08-21T00:00:00.000Z' }),
    ).toEqual([]);
  });

  it('accepts a plain YYYY-MM-DD transactionDate', async () => {
    expect(await errorsFor({ ...base, transactionDate: '2026-08-21' })).toEqual(
      [],
    );
  });

  it('rejects an impossible calendar date', async () => {
    // The exact shape an AI hallucination takes.
    expect(
      await errorsFor({ ...base, transactionDate: '2026-99-99' }),
    ).toContain('transactionDate');
  });

  it('rejects a non-date string', async () => {
    expect(
      await errorsFor({ ...base, transactionDate: 'yesterday' }),
    ).toContain('transactionDate');
  });

  it('rejects 31 February', async () => {
    expect(
      await errorsFor({ ...base, transactionDate: '2026-02-31' }),
    ).toContain('transactionDate');
  });

  it('does not weaken the rest of the DTO', async () => {
    const errors = await errorsFor({
      partyId: 'not-a-uuid',
      amount: -5,
      type: 'SIDEWAYS',
      transactionDate: '2026-08-21',
    });
    expect(errors).toEqual(
      expect.arrayContaining(['partyId', 'amount', 'type']),
    );
    expect(errors).not.toContain('transactionDate');
  });

  // ─── AI date sanitisation ───────────────────────────────────

  describe('Scan Khata extraction sanitising', () => {
    it('recognises a real date', () => {
      expect(isRealIsoDate('2026-08-21')).toBe(true);
    });

    it('rejects dates that only look real', () => {
      expect(isRealIsoDate('2026-99-99')).toBe(false);
      expect(isRealIsoDate('2026-02-31')).toBe(false);
      expect(isRealIsoDate('21/08/2026')).toBe(false);
      expect(isRealIsoDate('')).toBe(false);
      expect(isRealIsoDate(null)).toBe(false);
      expect(isRealIsoDate(42)).toBe(false);
    });

    it('keeps a valid sheet date', () => {
      const out = sanitizeExtraction({ date: '2026-08-21', records: [] });
      expect(out.date).toBe('2026-08-21');
    });

    it('blanks an invalid sheet date rather than correcting it', () => {
      // Dropped, not "fixed": a corrected date would look authoritative
      // while corresponding to nothing on the sheet.
      expect(sanitizeExtraction({ date: '2026-99-99', records: [] }).date).toBe(
        '',
      );
      expect(sanitizeExtraction({ date: 'sometime', records: [] }).date).toBe(
        '',
      );
    });

    it('drops an invalid per-record date but keeps the record', () => {
      const out = sanitizeExtraction({
        date: '2026-08-21',
        records: [
          { customerName: 'BHAVISH', amount: 1000, transactionDate: '2026-99-99' },
        ],
      });

      expect(out.records[0]).toEqual({ customerName: 'BHAVISH', amount: 1000 });
    });

    it('keeps a valid per-record date', () => {
      const out = sanitizeExtraction({
        date: '2026-08-21',
        records: [
          { customerName: 'RAHUL', amount: 500, transactionDate: '2026-08-20' },
        ],
      });

      expect(out.records[0].transactionDate).toBe('2026-08-20');
    });

    it('never alters amounts or names', () => {
      const out = sanitizeExtraction({
        date: 'garbage',
        records: [{ customerName: 'bhavish', amount: 1000.5 }],
      });

      expect(out.records[0]).toEqual({ customerName: 'bhavish', amount: 1000.5 });
    });

    it('survives malformed model output without throwing', () => {
      expect(() => sanitizeExtraction(null)).not.toThrow();
      expect(() => sanitizeExtraction({ records: 'not-an-array' })).not.toThrow();
      expect(() => sanitizeExtraction({ records: [null, 5] })).not.toThrow();
    });
  });
});
