export interface PublicProduct { id: string; name: string; description: string; price: number; photos: string[] }
export interface PublicVariant { id: string; color: string | null; size: string | null; price: number; quantity: number; available: boolean }
export interface Page<T> { content: T[]; page: number; totalPages: number; totalElements: number }
export interface CartItem { productId: string; variantId: string; name: string; color: string | null; size: string | null; price: number; quantity: number; stockQuantity: number; available: boolean }
export interface Cart { expiresAt: string; items: CartItem[] }
export interface QuoteResult { quotes: { id: number; name: string; price: number; deliveryDays: number }[]; unavailable: { id: number; name: string }[] }
