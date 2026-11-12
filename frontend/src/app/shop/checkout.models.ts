import { QuoteResult } from './shop.models';
import { CustomerAddress } from '../customer-account/customer.models';
export interface PickupAddress {
  postalCode: string; street: string; number: string; complement: string | null;
  district: string; city: string; state: string;
}
export interface PickupOption { available: boolean; address: PickupAddress | null; instructions: string | null }
export interface ReservationItem {
  productId: string; variantId: string; quantity: number; price: number;
  reserved: boolean; availableQuantity: number;
}
export interface Checkout {
  id: string | null; status: 'ACTIVE' | 'UNAVAILABLE' | 'EXPIRED' | 'CANCELLED';
  expiresAt: string | null; items: ReservationItem[];
}
export interface CheckoutQuote extends QuoteResult { quoteId: string; reservationId: string; expiresAt: string }
export interface CreateOrder {
  acceptPartial: boolean; fulfillmentType: 'DELIVERY' | 'PICKUP';
  addressId: string | null; quoteId: string | null; shippingServiceId: number | null;
}
export interface Order {
  id: string; reservationId: string; customerName: string; customerEmail: string;
  status: string; createdAt: string; expiresAt: string; total: number;
  fulfillmentType: 'DELIVERY' | 'PICKUP';
  items: { productId: string; variantId: string; name: string; color: string | null; size: string | null; unitPrice: number; quantity: number }[];
  delivery: { phone: string; address: CustomerAddress; shipping: { serviceId: number; name: string; price: number; deliveryDays: number } | null } | null;
  pickup: { phone: string; address: PickupAddress; instructions: string | null; cost: number } | null;
}
