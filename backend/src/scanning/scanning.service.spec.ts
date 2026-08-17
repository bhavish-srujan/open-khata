import { Test, TestingModule } from '@nestjs/testing';
import {
  BadRequestException,
  GatewayTimeoutException,
  InternalServerErrorException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ScanningService } from './scanning.service';

const mockGenerateContent = jest.fn();

jest.mock('@google/genai', () => {
  const actual = jest.requireActual('@google/genai');
  return {
    ...actual,
    GoogleGenAI: jest.fn().mockImplementation(() => ({
      models: { generateContent: mockGenerateContent },
    })),
  };
});

// Real class from the actual module, so `error instanceof ApiError` in the
// service sees a genuine instance — this is exactly what the SDK throws for
// any non-2xx Gemini response (confirmed against the SDK's own source: status
// is the numeric HTTP code, message is the raw JSON error body as a string).
const { ApiError } = jest.requireActual('@google/genai');

/**
 * Root cause pinned here: a real "Failed to connect to AI server (HTTP 500)"
 * report from Scan Khata traced back to Gemini itself returning 503
 * ("high demand") — confirmed by hitting the live Gemini API directly,
 * bypassing this backend entirely, and getting the identical error. The bug
 * was never in image handling or parsing; it was that every Gemini failure,
 * transient or not, collapsed into the same opaque 500. This suite pins the
 * classification that replaced that: a real Google-side outage/overload
 * (5xx or 429) is a 503 telling the caller to retry, a rejected request
 * (other 4xx — e.g. unreadable image content) is a 400 telling the caller to
 * pick a different image, and only a genuine unexpected failure stays a 500.
 */
describe('ScanningService', () => {
  let service: ScanningService;
  let configValues: Record<string, any>;

  const JPEG_BYTES = Buffer.from('fake-jpeg-bytes');

  const build = async () => {
    const module: TestingModule = await Test.createTestingModule({
      providers: [
        ScanningService,
        {
          provide: ConfigService,
          useValue: { get: (k: string) => configValues[k] },
        },
      ],
    }).compile();
    return module.get<ScanningService>(ScanningService);
  };

  beforeEach(async () => {
    jest.clearAllMocks();
    configValues = { GEMINI_API_KEY: 'test-key', AI_TIMEOUT_MS: 5000 };
    service = await build();
  });

  it('should be defined', () => {
    expect(service).toBeDefined();
  });

  describe('model comes from ConfigService, not a hardcoded string', () => {
    it('uses GEMINI_MODEL from config when set', async () => {
      configValues.GEMINI_MODEL = 'some-other-model';
      service = await build();
      mockGenerateContent.mockResolvedValue({
        text: '{"date":"","totalCollected":0,"records":[]}',
      });

      await service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      expect(mockGenerateContent.mock.calls[0][0].model).toBe(
        'some-other-model',
      );
    });

    it('falls back to gemini-flash-lite-latest when GEMINI_MODEL is unset', async () => {
      delete configValues.GEMINI_MODEL;
      service = await build();
      mockGenerateContent.mockResolvedValue({
        text: '{"date":"","totalCollected":0,"records":[]}',
      });

      await service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      expect(mockGenerateContent.mock.calls[0][0].model).toBe(
        'gemini-flash-lite-latest',
      );
    });

    it('never sends gemini-2.5-flash or gemini-3.5-flash by default — both are ruled out for this key', async () => {
      delete configValues.GEMINI_MODEL;
      service = await build();
      mockGenerateContent.mockResolvedValue({
        text: '{"date":"","totalCollected":0,"records":[]}',
      });

      await service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      const modelUsed = mockGenerateContent.mock.calls[0][0].model;
      expect(modelUsed).not.toBe('gemini-2.5-flash');
      expect(modelUsed).not.toBe('gemini-3.5-flash');
    });
  });

  describe('input validation, before any Gemini call', () => {
    it('rejects a missing/empty image', async () => {
      await expect(
        service.extractLedgerData(Buffer.alloc(0), 'image/jpeg'),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(mockGenerateContent).not.toHaveBeenCalled();
    });

    it('rejects an unsupported MIME type', async () => {
      await expect(
        service.extractLedgerData(JPEG_BYTES, 'application/pdf'),
      ).rejects.toBeInstanceOf(BadRequestException);
      expect(mockGenerateContent).not.toHaveBeenCalled();
    });

    it.each([
      'image/png',
      'image/jpeg',
      'image/webp',
      'image/heic',
      'image/heif',
    ])('accepts %s and proceeds to call Gemini', async (mimeType) => {
      mockGenerateContent.mockResolvedValue({
        text: '{"date":"","totalCollected":0,"records":[]}',
      });
      await service.extractLedgerData(JPEG_BYTES, mimeType);
      expect(mockGenerateContent).toHaveBeenCalledTimes(1);
    });
  });

  describe('mock mode (no GEMINI_API_KEY)', () => {
    it('returns mock data without calling Gemini', async () => {
      configValues = {};
      service = await build();

      const result = await service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      expect(result.records.length).toBeGreaterThan(0);
      expect(mockGenerateContent).not.toHaveBeenCalled();
    });
  });

  describe('successful extraction', () => {
    it('parses and returns the structured JSON Gemini responds with', async () => {
      const payload = {
        date: '2026-08-15',
        totalCollected: 1500,
        records: [{ customerName: 'Rahul Traders', amount: 1500 }],
      };
      mockGenerateContent.mockResolvedValue({ text: JSON.stringify(payload) });

      const result = await service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      expect(result).toEqual(payload);
    });

    it('sends the image as inlineData with the caller-provided mimeType, base64-encoded', async () => {
      mockGenerateContent.mockResolvedValue({
        text: '{"date":"","totalCollected":0,"records":[]}',
      });

      await service.extractLedgerData(JPEG_BYTES, 'image/png');

      const call = mockGenerateContent.mock.calls[0][0];
      const imagePart = call.contents[0].parts.find((p: any) => p.inlineData);
      expect(imagePart.inlineData.mimeType).toBe('image/png');
      expect(imagePart.inlineData.data).toBe(JPEG_BYTES.toString('base64'));
    });

    it('an empty extraction (no records) is still a success, not an error', async () => {
      mockGenerateContent.mockResolvedValue({
        text: '{"date":"2026-08-15","totalCollected":0,"records":[]}',
      });

      const result = await service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      expect(result.records).toEqual([]);
    });
  });

  describe('Gemini outage/overload — the actual root cause found in production', () => {
    it('maps a real 503 UNAVAILABLE to ServiceUnavailableException, not a generic 500', async () => {
      mockGenerateContent.mockRejectedValue(
        new ApiError({
          status: 503,
          message: JSON.stringify({
            error: {
              code: 503,
              message: 'This model is currently experiencing high demand.',
              status: 'UNAVAILABLE',
            },
          }),
        }),
      );

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(ServiceUnavailableException);
    });

    it('maps a 429 rate-limit to ServiceUnavailableException as well', async () => {
      mockGenerateContent.mockRejectedValue(
        new ApiError({ status: 429, message: '{"error":{"code":429}}' }),
      );

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(ServiceUnavailableException);
    });

    it('maps any other Gemini 5xx to ServiceUnavailableException too', async () => {
      mockGenerateContent.mockRejectedValue(
        new ApiError({ status: 500, message: '{"error":{"code":500}}' }),
      );

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(ServiceUnavailableException);
    });
  });

  describe('Gemini rejects the request itself (bad image content)', () => {
    it('maps a 400 INVALID_ARGUMENT to BadRequestException, distinct from an outage', async () => {
      mockGenerateContent.mockRejectedValue(
        new ApiError({
          status: 400,
          message: JSON.stringify({
            error: {
              code: 400,
              message: 'Unable to process input image.',
              status: 'INVALID_ARGUMENT',
            },
          }),
        }),
      );

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(BadRequestException);
    });
  });

  describe('timeout', () => {
    it('a Gemini call that never settles within AI_TIMEOUT_MS becomes a 504, not a 500', async () => {
      mockGenerateContent.mockReturnValue(new Promise(() => {})); // never resolves
      configValues.AI_TIMEOUT_MS = 50;
      service = await build();

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(GatewayTimeoutException);
    });
  });

  describe('Gemini responds but not with usable JSON', () => {
    it('malformed JSON (e.g. markdown-wrapped) is a distinct 500, not silently swallowed or a fake success', async () => {
      mockGenerateContent.mockResolvedValue({
        text: '```json\n{"date":"2026-08-15"}\n```',
      });

      const attempt = service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      await expect(attempt).rejects.toBeInstanceOf(
        InternalServerErrorException,
      );
      await expect(attempt).rejects.toMatchObject({
        message: expect.stringContaining('could not extract structured data'),
      });
    });

    it('an empty response (no text at all) is also a distinct failure, not a fake success', async () => {
      mockGenerateContent.mockResolvedValue({ text: '' });

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(InternalServerErrorException);
    });

    it('never fabricates ledger data when extraction actually failed', async () => {
      mockGenerateContent.mockResolvedValue({ text: 'not json at all' });

      const attempt = service.extractLedgerData(JPEG_BYTES, 'image/jpeg');

      await expect(attempt).rejects.toThrow();
      // Confirms the failure path never returns a resolved value shaped like
      // a real extraction result — this can never be mistaken for real data.
    });
  });

  describe('an unexpected, unclassified failure', () => {
    it('still surfaces as a 500 rather than being hidden or reported as 200', async () => {
      mockGenerateContent.mockRejectedValue(
        new Error('completely unexpected failure'),
      );

      await expect(
        service.extractLedgerData(JPEG_BYTES, 'image/jpeg'),
      ).rejects.toBeInstanceOf(InternalServerErrorException);
    });
  });
});
