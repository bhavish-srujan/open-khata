import { Injectable } from '@nestjs/common';
import PDFDocument = require('pdfkit');
import { LedgerEntry } from '../../ledger/ledger-entry.entity';
import { Party } from '../../party/party.entity';

@Injectable()
export class PdfService {
  
  async generateBusinessStatement(
    businessName: string,
    party: Party | null,
    entries: LedgerEntry[],
    startDate: Date,
    endDate: Date
  ): Promise<Buffer> {
    return new Promise((resolve, reject) => {
      const doc = new PDFDocument({ margin: 50 });
      const buffers: Buffer[] = [];

      doc.on('data', buffers.push.bind(buffers));
      doc.on('end', () => {
        const pdfData = Buffer.concat(buffers);
        resolve(pdfData);
      });
      doc.on('error', reject);

      // Header
      doc
        .fontSize(20)
        .font('Helvetica-Bold')
        .text(businessName, { align: 'center' })
        .moveDown();

      doc
        .fontSize(14)
        .text('Business Statement', { align: 'center' })
        .moveDown();

      if (party) {
        doc.fontSize(12).font('Helvetica').text(`Customer/Supplier: ${party.name} (${party.phone})`);
      }
      doc.text(`Period: ${startDate.toLocaleDateString()} - ${endDate.toLocaleDateString()}`);
      doc.moveDown(2);

      // Table Header
      let y = doc.y;
      doc.font('Helvetica-Bold').fontSize(10);
      doc.text('Date', 50, y);
      doc.text('Type', 150, y);
      doc.text('Amount', 250, y);
      doc.text('Note', 350, y);
      doc.text('Status', 450, y);
      
      doc.moveTo(50, y + 15).lineTo(550, y + 15).stroke();
      
      doc.font('Helvetica').fontSize(10);
      y += 20;

      let totalGave = 0;
      let totalGot = 0;

      // Table Rows
      for (const entry of entries) {
        if (y > 700) {
          doc.addPage();
          y = 50;
        }

        const dateStr = entry.createdAt.toLocaleDateString();
        const typeStr = entry.type;
        const amountStr = `Rs ${entry.amount}`;
        const noteStr = entry.note || '-';
        const statusStr = entry.status;

        if (entry.type === 'GAVE') totalGave += Number(entry.amount);
        if (entry.type === 'GOT') totalGot += Number(entry.amount);

        doc.text(dateStr, 50, y);
        doc.text(typeStr, 150, y);
        doc.text(amountStr, 250, y);
        doc.text(noteStr, 350, y);
        doc.text(statusStr, 450, y);

        y += 20;
      }

      doc.moveTo(50, y).lineTo(550, y).stroke();
      y += 10;

      // Summary
      doc.font('Helvetica-Bold').fontSize(12);
      doc.text('Summary', 50, y);
      y += 20;
      doc.font('Helvetica').fontSize(10);
      doc.text(`Total GAVE: Rs ${totalGave}`, 50, y);
      y += 15;
      doc.text(`Total GOT: Rs ${totalGot}`, 50, y);
      y += 15;
      const net = totalGot - totalGave;
      doc.font('Helvetica-Bold');
      doc.text(`Net: Rs ${Math.abs(net)} ${net >= 0 ? '(Positive)' : '(Negative)'}`, 50, y);

      doc.end();
    });
  }
}
