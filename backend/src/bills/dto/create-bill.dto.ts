export class CreateBillDto {
  partyId: string;
  totalAmount: number;
  items: any[];
  isGst: boolean;
}
