import { Injectable, Logger, BadRequestException, InternalServerErrorException } from '@nestjs/common';
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
        partyName: 'Rahul Traders',
        entries: [
          { date: '2023-10-25', amount: 500, type: 'GAVE', notes: 'Rice and Dal' },
          { date: '2023-10-26', amount: 200, type: 'GOT', notes: 'Cash paid' }
        ]
      };
    }

    try {
      const prompt = `
      You are an expert at extracting accounting data from handwritten ledger (khata) images.
      Extract the party name (customer/supplier) and all ledger entries (credits/debits).
      For each entry, extract the date, amount, type (either 'GAVE' if credit was given or 'GOT' if payment was received), and any notes/particulars.
      Return the data strictly as a JSON object with this format:
      {
        "partyName": "Name of the party",
        "entries": [
          { "date": "YYYY-MM-DD", "amount": 100.50, "type": "GAVE" | "GOT", "notes": "Item description" }
        ]
      }
      Do not include any markdown formatting or text outside the JSON object.
      `;

      const response = await this.ai.models.generateContent({
        model: 'gemini-2.5-flash',
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

      const text = response.text;
      if (!text) {
        throw new Error('No response from Gemini');
      }

      const parsedData = JSON.parse(text);
      return parsedData;

    } catch (error) {
      this.logger.error('Failed to extract data using Gemini', error);
      throw new InternalServerErrorException('Failed to process image');
    }
  }
}
