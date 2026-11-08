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

### Catalog and planned inventory management

The item-type, product, manual inventory, and inventory audit APIs described
below are implemented. Inventory reservations, automatic sale deductions,
photos, management screens, promotions, and store-member permissions remain
planned.

#### Item types and variants

A merchant can create, view, update, and delete item types within their store.
An item type has a name, such as `Camiseta`, `Calça`, or `Tênis`, and defines
which variant attributes its products use, such as color and size. For size,
the merchant can choose a numeric or letter-based format. Numeric sizes support
footwear, while letter-based sizes support values such as `PP`, `P`, `M`, and
`G` for clothing. These are examples, not fixed platform-wide lists.

The authenticated item-type API at `/api/item-types` supports creation, lookup,
paginated listing, updates, deactivation, reactivation at `/{id}/reactivate`,
and paginated history at `/{id}/history`. Names are unique within each store,
ignoring case and leading or trailing spaces, and stay reserved when a type is
inactive. A type records whether it uses color or size, and a size format
(`NUMERIC` or `LETTER`) is required exactly when size is enabled. Size values
are free text in both formats, allowing examples such as `2XL` or `38/40`.
Individual products define their own color and size values.

Each change records its actor and before/after values, including deactivation
and reactivation. Types referenced by products, whether active or inactive,
cannot be deactivated or have their variant configuration changed, although
renaming remains allowed. Types physically deleted before soft deletion was
introduced cannot be recovered.

The item-type endpoints require a bearer access token and determine the store
from the authenticated account, not from a client-supplied store identifier.
For example, `POST /api/item-types` accepts:

```json
{
  "name": "Camiseta",
  "hasColor": true,
  "hasSize": true,
  "sizeFormat": "LETTER"
}
```

`PUT /api/item-types/{id}` accepts the same complete representation.
Creation returns `201`, updates and reads return `200`, deletion returns `204`,
duplicate names return `409`, invalid input returns `400`, and an item type
outside the authenticated store returns `404`. Lists and history accept
zero-based `page` and `size` parameters; size defaults to 20 and is limited
to 100. The list defaults to active types; `active=false` lists inactive types.
History remains available after deactivation.

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

The authenticated `/api/products` API supports product creation, lookup,
paginated listing, update, deactivation, reactivation, and paginated history.
`/{id}/variants` supports adding and listing variants; individual variants can
have their optional price updated, be deactivated, and be reactivated at
`/{id}/variants/{variantId}/reactivate`. Product and variant lists default to
active records and accept `active=false`; pagination uses the same limits as
item types. A product's individual variant states are preserved while the
product is inactive, but none of its variants should be treated as available
for sale until the product is active again. An active product may have no
active variants, in which case it has no variant available for sale.

Products have a non-negative default price in BRL, and a variant may override
it with its own non-negative price; prices have at most two decimal places.
The variant response contains both the optional price and effective price.
A type without color or size has exactly one unique combination with neither
attribute. Combinations and product SKUs remain reserved when inactive, and
uniqueness ignores case and leading or trailing spaces. Product and variant
history records the actor and field changes transactionally. Changing a
product's item type and editing a variant's color or size are not supported;
variant prices can be changed or cleared.

For example, `POST /api/products` accepts:

```json
{
  "itemTypeId": "00000000-0000-0000-0000-000000000001",
  "sku": "SHIRT-01",
  "name": "Camiseta",
  "description": "Camiseta de algodão",
  "price": 99.90,
  "variants": [
    { "color": "Preta", "size": "2XL" },
    { "color": "Amarela", "size": "M", "price": 109.90 }
  ]
}
```

The authenticated inventory API manages physical stock per variant. A variant
starts at zero, including variants created before inventory was introduced.
The merchant may record positive whole-unit entries and exits with a reason;
stock cannot become negative. Inactive products and variants retain their
physical stock and can still be counted and adjusted; their stock is not
available for sale while inactive. Each operation records the actor, time,
reason, and before/after quantities in the same transaction.

`GET /api/inventory/products/{productId}/variants/{variantId}` returns
`quantity` and `version`. `POST` to the same path followed by `/movements`
accepts `{"type":"ENTRY","quantity":5,"reason":"Delivery"}` or an `EXIT`.
`GET` on `/movements` returns paginated history. Quantities are whole numbers
from zero through 2,147,483,647; an entry or exit requires at least one unit.

Every entry or exit requires an `Idempotency-Key` header containing a UUID.
The client generates a new UUID for each distinct movement and retains it for
all retries, including after a timeout. Repeating the same successful request
with the same key returns the original `201` response without changing stock
or history again. Reusing the key in the same store for a different actor,
product, variant, movement type, quantity or reason returns `409`. Reasons
are compared after trimming leading and trailing whitespace. Keys are retained
with their movement history; failed transactions do not reserve a key. Keys
are scoped to the authenticated store, not supplied as a store identifier.
Previously recorded movements do not have a retry key. This protection applies
to manual entries and exits, not audit counts or future checkout operations.

`POST /api/inventory/audits` opens an audit, `GET /api/inventory/audits` lists
audits, and `GET /api/inventory/audits/{id}` retrieves one. Only one audit per
store may be open. Opening and closing record the actor and time.
`POST /api/inventory/audits/{id}/counts` records a count and immediately updates
stock, even if the counted quantity is unchanged:

```json
{
  "productId": "00000000-0000-0000-0000-000000000001",
  "variantId": "00000000-0000-0000-0000-000000000002",
  "expectedVersion": 0,
  "quantity": 3,
  "reason": "Physical count"
}
```

Obtain `expectedVersion` from the balance before counting. If stock changed
since then, the request returns `409` and must be reviewed against the current
balance; even changes that restore the old quantity are detected. Counts and
their full history can be listed with `GET /api/inventory/audits/{id}/counts`.
`POST /api/inventory/audits/{id}/close` closes the audit; closed audits reject
new counts but remain readable. Lists accept zero-based `page` and `size`,
defaulting to 20 with a maximum of 100. Tenant scope is derived from the
authenticated account, not supplied by the client.

Reservation during checkout, automatic deduction when a sale is completed, and
reservation expiration or cancellation are not yet implemented.

The authenticated `GET /api/inventory/products` lists product stock summaries,
filtered by product status (`active=true` by default, or `active=false`) and
paginated. `GET /api/inventory/products/{productId}` returns one summary,
including an inactive product. Each summary has SKU, name, description, product
status, `physicalQuantity` and `enabledQuantity`. Physical quantity includes
all variants, active or inactive. Enabled quantity includes only active
variants of an active product; it is zero when the product is inactive.
These are physical balances, not guaranteed sale availability: reservations and
checkout are not implemented.

`GET /api/inventory/products/{productId}/variants` lists all variants of that
product with color, size, status, physical quantity, balance version and
enabled quantity. Variants with no recorded movement have quantity and version
zero. The list is paginated, with the same limits as other inventory lists.
All three endpoints enforce the authenticated store boundary. Totals and
variant pages are separate requests and can reflect different moments if stock
changes between requests.

The future management screen should show SKU, photo, description, total stock
and variant-level stock; no screen exists yet. Photo storage and management
still require a decision. Product IDs provide a stable identity for future
photo associations.

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