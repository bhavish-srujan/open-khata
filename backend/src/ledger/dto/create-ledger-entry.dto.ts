export class CreateLedgerEntryDto {
  partyId: string;
  amount: number;
  type: 'GAVE' | 'GOT';
  note?: string;
  paymentMethod?: string;
}
