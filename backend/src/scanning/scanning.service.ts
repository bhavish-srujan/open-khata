import { Injectable, Logger, BadRequestException, InternalServerErrorException, GatewayTimeoutException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { GoogleGenAI } from '@google/genai';

@Injectable()
export class ScanningService {
  private readonly logger = new Logger(ScanningService.name);
  private ai: GoogleGenAI;

  constructor(private configService: ConfigService) {
    const apiKey = this.configService.get<string>('GEMINI_API_KEY');
    if (apiKey) {
      this.ai = new GoogleGenAI({ apiKey });
    } else {
      this.logger.warn('GEMINI_API_KEY not found. AI Scanning will return mock data.');
    }
  }

  async extractLedgerData(imageBuffer: Buffer, mimeType: string): Promise<any> {
    if (!imageBuffer) {
      throw new BadRequestException('Image is required');
    }

    if (!this.ai) {
      // Return mock data if API key is not configured
      return {
        date: '2023-10-25',
        totalCollected: 700.0,
        records: [
          { customerName: 'Rahul Traders', amount: 500.0 },
          { customerName: 'Pndi', amount: 200.0 }
        ]
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
        model: 'gemini-flash-latest',
        contents: [
          {
            role: 'user',
            parts: [
              { text: prompt },
              { inlineData: { data: imageBuffer.toString('base64'), mimeType: mimeType } }
            ]
          }
        ],
        config: {
          responseMimeType: "application/json",
          temperature: 0.1
        }
      });

      const timeoutMs = Number(this.configService.get<number>('AI_TIMEOUT_MS') || 15000);
      const timeout = new Promise((_, reject) => setTimeout(() => reject(new Error('AI timeout')), timeoutMs));
      const response: any = await Promise.race([aiCall, timeout]);

      const text = response?.text;
      if (!text) {
        throw new Error('No response from Gemini');
      }

      const parsedData = JSON.parse(text);
      return parsedData;

    } catch (error) {
      this.logger.error('Failed to extract data using Gemini', error?.message || error);
      if (error?.message === 'AI timeout') {
        throw new GatewayTimeoutException('AI request timed out. Try again later.');
      }
      throw new InternalServerErrorException('Failed to process image');
    }
  }
}
