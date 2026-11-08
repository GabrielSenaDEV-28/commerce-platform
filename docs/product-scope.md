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

The initial catalog focuses on clothing and also supports footwear. Merchants
configure the types and variations relevant to their products rather than
relying on a fixed platform-wide list.

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

### Merchant onboarding and local authentication API (MVP)

Merchant self-registration is available through the public
`POST /api/merchants` endpoint. It creates a merchant, its initial store, and
the merchant's local email/password account in one transaction, returning
`201 Created`; invalid input returns `400 Bad Request`, and an already-used
subdomain or email returns `409 Conflict`.

The request includes `merchantName`, `storeName`, `subdomain`, `email`, and
`password`. Configure `JWT_SECRET_BASE64` with at least 32 cryptographically
random bytes encoded as Base64 before starting the application. For local
development, run `export JWT_SECRET_BASE64="$(openssl rand -base64 32)"`;
keep it outside the repository and do not log or share it.

Local authentication is available through `POST /api/auth/login`,
`POST /api/auth/refresh`, and `POST /api/auth/logout`. Access JWTs expire after
15 minutes; refresh tokens expire after 7 days and are rotated on refresh.
Logout revokes the submitted refresh token; an already-issued access token
remains valid until it expires. Email
verification and external identity providers, including Google, are not part
of this version. The access account is separate from the Merchant profile so a
future provider identity can be linked without treating provider email as its
stable identifier.

Registration and authentication endpoints are public. They can be abused;
production exposure requires rate limiting and an abuse-prevention decision.

### Planned catalog and inventory management

The following requirements describe planned functionality; the catalog,
inventory management, management screens, and store-member permissions are not
implemented yet.

#### Item types and variants

A merchant can create, view, update, and delete item types within their store.
An item type has a name, such as `Camiseta`, `Calça`, or `Tênis`, and defines
which variant attributes its products use, such as color and size. For size,
the merchant can choose a numeric or letter-based format. Numeric sizes support
footwear, while letter-based sizes support values such as `PP`, `P`, `M`, and
`G` for clothing. These are examples, not fixed platform-wide lists.

The behavior of editing or deleting a type already used by products must be
defined before implementation so existing products and their inventory remain
consistent.

#### Products and stock

A merchant can add and manage a product in their store by providing its SKU,
description, item type, available colors and sizes when applicable, and a
photo. Each product has one SKU, unique within its store; different stores may
use the same SKU. All variants of a product share that SKU.

The merchant selects only the combinations actually sold, rather than
automatically creating every possible combination. Each combination has its own
internal identifier, so its inventory can be managed separately even though the
SKU is shared. For example, a shirt may have `preta / P` and `amarela / M`
variants without requiring every other color-and-size combination.

The merchant can add and manage stock for a selected variant. The management
screen lists products with their SKU, photo, description, and total available
stock, and lets the merchant inspect the available stock for each variant. For
example, a product with three `preta / P` units and four `amarela / M` units
has seven available units in total.

Product pricing, photo storage and management, and detailed stock adjustment
rules require decisions before implementation.

#### Audit, access, and isolation

Item types, products, variants, and stock records need creation and latest
update timestamps and actor attribution where applicable. Changes must be
traceable through a history identifying who changed what and when, including
stock adjustments. Audit data must not expose passwords, tokens, or other
secrets.

In the future, a merchant can create accounts for sales staff and assign
different permissions. The eventual authorization policy must check the
authenticated actor's permissions on the backend, not rely on the client.
Products, item types, variants, stock, and their history belong to a store:
every relevant read and write must enforce that store boundary. Lists that can
grow must support pagination.

## 5. Initial Scope

The first version will focus on the minimum end-to-end commerce flow required
to create and process an order.

It includes:

- Store creation and basic configuration.
- Platform-provided store subdomain.
- Merchant local email/password login.
- Product and variant management.
- Store-configured item types and variant attributes.
- Inventory management.
- Catalog and inventory change history.
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
- Granular authorization, sales staff accounts, and store member management.
- External identity providers, including Google, and email verification.
- Advanced storefront customization.
- Discounts, coupons, and promotional campaigns.
- Customer notifications.
- Returns, refunds, and payment reconciliation.
- Product reviews and ratings.
- Analytics and reporting.
- Real-time customer experiences.
- Live-commerce and acquisition-channel tracking.