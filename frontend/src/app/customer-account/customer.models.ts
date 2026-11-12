export interface CustomerProfile { name: string; email: string; phone: string | null }
export interface AddressInput {
  recipient: string; postalCode: string; street: string; number: string;
  complement: string; district: string; city: string; state: string;
}
export interface CustomerAddress extends Omit<AddressInput, 'complement'> {
  id: string; country: string; complement: string | null;
}
