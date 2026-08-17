import {
  Injectable,
  Logger,
  BadRequestException,
  InternalServerErrorException,
  GatewayTimeoutException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiError, GoogleGenAI } from '@google/genai';

/** Used when GEMINI_MODEL is unset. Not a fallback in the retry sense — this
 *  only ever applies once, at startup, before any request is ever made. */
const DEFAULT_MODEL = 'gemini-flash-lite-latest';

@Injectable()
export class ScanningService {
  private readonly logger = new Logger(ScanningService.name);
  private ai: GoogleGenAI;
  private readonly model: string;

  constructor(private configService: ConfigService) {
    const apiKey = this.configService.get<string>('GEMINI_API_KEY');
    this.model =
      this.configService.get<string>('GEMINI_MODEL')?.trim() || DEFAULT_MODEL;
    if (apiKey) {
      this.ai = new GoogleGenAI({ apiKey });
    } else {
      this.logger.warn(
        'GEMINI_API_KEY not found. AI Scanning will return mock data.',
      );
    }
  }

  /** Formats Gemini's inline-image input actually accepts. Anything else is
   *  rejected before spending a paid API call on a request that cannot work. */
  private static readonly SUPPORTED_MIME_TYPES = new Set([
    'image/png',
    'image/jpeg',
    'image/webp',
    'image/heic',
    'image/heif',
  ]);

  async extractLedgerData(imageBuffer: Buffer, mimeType: string): Promise<any> {
    if (!imageBuffer || imageBuffer.length === 0) {
      throw new BadRequestException('Image is required');
    }

    if (
      !ScanningService.SUPPORTED_MIME_TYPES.has((mimeType || '').toLowerCase())
    ) {
      throw new BadRequestException(
        `Unsupported image type "${mimeType}". Use JPEG, PNG, WEBP or HEIC.`,
      );
    }

    if (!this.ai) {
      // Return mock data if API key is not configured
      return {
        date: '2023-10-25',
        totalCollected: 700.0,
        records: [
          { customerName: 'Rahul Traders', amount: 500.0 },
          { customerName: 'Pndi', amount: 200.0 },
        ],
      };
    }

    try {
      const prompt = `
      You are an expert at extracting accounting data from handwritten ledger (khata) images.
      The user is uploading a "Daily Collection Sheet" where multiple customers pay money on a single day.
      Extract the total collected amount and the list of individual records.
      For each record, extract the customer's name (or code) and the amount they paid/received.
      Return the data strictly as a JSON object with this format:
      {
        "date": "YYYY-MM-DD",
        "totalCollected": 5000.0,
        "records": [
          { "customerName": "Name of customer", "amount": 500.0 }
        ]
      }
      If the exact date is not visible, use today's date or leave it blank.
      Do not include any markdown formatting or text outside the JSON object.
      `;

      // Run AI call with an enforced timeout to avoid long buffering for clients
      const aiCall = this.ai.models.generateContent({
        model: this.model,
        contents: [
          {
            role: 'user',
            parts: [
              { text: prompt },
              {
                inlineData: {
                  data: imageBuffer.toString('base64'),
                  mimeType: mimeType,
                },
              },
            ],
          },
        ],
        config: {
          responseMimeType: 'application/json',
          temperature: 0.1,
        },
      });

      const timeoutMs = Number(
        this.configService.get<number>('AI_TIMEOUT_MS') || 15000,
      );
      const timeout = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('AI timeout')), timeoutMs),
      );
      const response: any = await Promise.race([aiCall, timeout]);

      const text = response?.text;
      if (!text) {
        throw new Error('No response from Gemini');
      }

      try {
        return JSON.parse(text);
      } catch (parseError) {
        // Gemini answered, but not with the strict JSON the prompt demands
        // (seen in practice: markdown code fences, or truncated output on a
        // very cluttered image). Distinct from every failure below: the
        // model was reachable and did respond, so retrying blind is unlikely
        // to help without a prompt/response-schema fix. Logged in full since
        // it is model output, not user or credential data.
        this.logger.error(
          `Gemini response was not valid JSON: ${text.slice(0, 500)}`,
        );
        throw new InternalServerErrorException(
          'AI could not extract structured data from this image. Try a clearer photo.',
        );
      }
    } catch (error) {
      if (error instanceof InternalServerErrorException) {
        throw error; // Already classified just above — do not re-wrap.
      }

      if (error?.message === 'AI timeout') {
        this.logger.error('Gemini request timed out');
        throw new GatewayTimeoutException(
          'AI request timed out. Try again later.',
        );
      }

      // ApiError.status is Gemini's own HTTP status, not ours — most visible
      // in practice as 503 ("model overloaded, try again") which the SDK
      // otherwise indistinguishable from a real bug in our own code. A 5xx
      // here is Google's outage, not ours, so it is reported as one; a 4xx
      // means Gemini itself rejected the request (e.g. unreadable/unsafe
      // image content) and is the caller's to fix, not retry unchanged.
      if (error instanceof ApiError) {
        this.logger.error(`Gemini API error ${error.status}: ${error.message}`);
        if (error.status === 429 || error.status >= 500) {
          throw new ServiceUnavailableException(
            'The AI service is temporarily busy. Please try again in a moment.',
          );
        }
        throw new BadRequestException(
          'The AI could not process this image. Try a clearer or different photo.',
        );
      }

      this.logger.error(
        'Failed to extract data using Gemini',
        error?.message || error,
      );
      throw new InternalServerErrorException('Failed to process image');
    }
  }
}
