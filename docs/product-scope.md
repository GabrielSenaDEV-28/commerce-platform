# Product Scope

## 1. Problem

Small and medium-sized retailers need a reliable way to sell products online
while managing the core operations involved in digital commerce.

As sales volume and operational complexity grow, managing product catalogs,
inventory, customer orders, payments, and fulfillment across disconnected or
manual processes becomes increasingly difficult, increasing operational effort
and the risk of inconsistent information.

## 2. Target Users

### Merchant

A business that uses the platform to operate an online store, managing its
catalog, inventory, orders, payments, and fulfillment.

### Store Operator

A person authorized by a merchant to perform operational activities within
the store, such as managing products, inventory, and orders.

### Customer

A person who accesses a store to browse products, place orders, make payments,
and track purchases.

## 3. Product Proposal

Provide a multi-tenant e-commerce SaaS where merchants can operate independent
online stores through a shared platform.

Each store can manage its own catalog, inventory, customers, orders, payments,
and fulfillment while remaining logically isolated from other stores using
the platform.

Each store receives a platform-provided subdomain. The platform is designed to
also support custom domains, allowing merchants to expose their storefront
through their own domain when this capability is enabled.

Customers access the merchant's storefront through its configured domain,
browse the catalog, purchase products, and follow the lifecycle of their orders.

## 4. Core Business Flow

1. A merchant is onboarded onto the platform and a store is created.

2. The platform provides the store with a default subdomain.

3. The merchant configures the store and makes products and their variants
   available for sale.

4. A customer accesses the store and browses its catalog.

5. The customer may estimate shipping costs before starting a purchase. This
   operation does not reserve inventory.

6. The customer adds products to the cart. The cart represents purchase intent
   and does not guarantee price or inventory availability.

7. When the customer starts checkout, the platform validates current product
   availability and commercial conditions and creates a temporary inventory
   reservation.

8. The checkout reservation is valid for a limited period. If the checkout
   expires before completion, the reserved inventory becomes available again.

9. During checkout, the customer provides the required information, selects a
   shipping option and chooses a payment method.

10. When checkout is completed, an order is created preserving the commercial
    conditions of the purchase, including item prices, quantities, shipping cost
    and total amount.

11. If the selected payment method requires asynchronous confirmation, the
    inventory remains reserved while the payment is pending, subject to the
    payment expiration rules.

12. When payment is confirmed, the order is confirmed and the reserved inventory
    is committed to the sale.

13. If payment expires or the order is cancelled before confirmation, the
    inventory reservation is released.

14. After confirmation, the merchant fulfills the order and the customer can
    follow its delivery lifecycle.

## 5. Initial Scope

The first version will focus on the minimum end-to-end commerce flow required
to create and process an order.

It includes:

- Store creation and basic configuration.
- Platform-provided store subdomain.
- Product and variant management.
- Inventory management.
- Product catalog browsing.
- Shopping cart.
- Checkout with temporary inventory reservation.
- Order creation.
- Inventory reservation expiration and release.
- Basic order lifecycle.

## 6. Out of Scope

The following capabilities are intentionally excluded from the initial version:

- Custom domain connection.
- Real payment provider integration.
- Real shipping carrier integration.
- Authentication and authorization.
- Discounts, coupons, and promotions.
- Product reviews and ratings.
- Returns and refunds.
- Invoicing and tax integration.
- Customer notifications.
- Real-time features.
- Advanced storefront customization.
- Analytics and reporting.

## 7. Future Capabilities

Potential future capabilities include:

- Custom domain connection.
- Payment provider integrations, including PIX and credit cards.
- Shipping carrier integrations and shipment tracking.
- Authentication, authorization, and store member management.
- Advanced storefront customization.
- Discounts, coupons, and promotional campaigns.
- Customer notifications.
- Returns, refunds, and payment reconciliation.
- Product reviews and ratings.
- Analytics and reporting.
- Real-time customer experiences.
- Live-commerce and acquisition-channel tracking.