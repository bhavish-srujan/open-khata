export class CreatePartyDto {
  name: string;
  phone: string;
  type: 'CUSTOMER' | 'SUPPLIER';
  gstin?: string;
  flatBuilding?: string;
  areaLocality?: string;
  pincode?: string;
  city?: string;
  state?: string;
  isShippingSame?: boolean;
}
