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
   fulfillment option and chooses a payment method.

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

### Initial frontend (MVP)

The Angular frontend provides initial merchant and customer registration and
login screens and a storefront home with a paginated public product listing.
Customer registration, login and storefront use the store's hostname.
Access tokens are held only in page memory. Browser sessions use a host-only
HttpOnly refresh cookie and restore authentication before initial rendering and
route guards, including after reload. Access is renewed before expiration;
concurrent renewals share one request. A rejected authenticated request can be
retried once after successful renewal; transport/server failures never cause
automatic write retries. An invalid refresh session requests login again, while
network/server failures show a retryable session warning and block cart changes
without deleting account contents. Successful login always opens
the store home for both customer and merchant, ignoring previous return targets;
already authenticated visits to login also open the home. Customers resume
checkout through the recovered bag, not an automatic settings/checkout
redirect. Expiration takes precedence over unsaved changes; no form
drafts or authentication tokens are saved in browser storage.
The customer account provides profile editing, address management, and an
order list through existing authenticated APIs. Payment methods, email and
password changes are explicitly unavailable and collect no payment credentials.
Product cards open a public product page with a photo gallery, description,
variant selection, effective variant prices, quantity validation and product
shipping estimates by postal code. Quick add from the listing opens the same
variant-selection component in a native modal dialog, without choosing an
arbitrary variant. The shopping bag supports current prices, quantity changes,
removal and cart shipping estimates through the existing public APIs.
Cart items remain in PostgreSQL. Anonymous access tokens are stored in
origin-scoped `sessionStorage` to survive reloads in the same tab; no customer
authentication tokens are stored there. On customer login, authenticated
registration or restored browser session, the frontend recovers
the account cart and merges the anonymous bag through
`POST /api/public/cart/customer`, sending customer Bearer and optional anonymous
`Cart-Token`. The response is `{token, expiresAt, cart}`. Account cart tokens remain
in page memory and require the owning customer's Bearer on every use, including
checkout and shipping. Independent device tokens permit recovery on another
device without invalidating existing access. Logout/session expiry clear account
cart access and visible items; subsequent login recovers the server cart.
An anonymous bag alone does not survive closing the tab; account recovery needs
an authenticated session; the refresh cookie restores it on this store's origin.
Customer registration signs in immediately and performs the same anonymous merge.
After a confirmed merge the anonymous capability is removed; logout hides the
account bag instead of turning it into the next visitor's anonymous bag.
Transparent renewal preserves cart capability, contents and pending checkout
state instead of repeating the merge.

Merging is transactional and idempotent: matching variants have their quantities
summed, other products and unavailable variants are retained, and consumed
anonymous sources cannot be reused by another customer. Repeating the same merge
after a lost response does not double quantities. Reservations, item-count limits
and integer overflow produce explicit conflicts without silent truncation.
Recovery failures remain visible in a global banner with retry, not an empty-cart
success state. An expired anonymous source is discarded only by explicit action.
Blocked browser storage produces a visible warning instead of promising reload
recovery. No prices, availability or checkout snapshots are trusted from storage.
"Buy now" adds the selection and opens the bag review without clearing other
items; it does not create an order or charge the customer. The bag links to an
authenticated customer checkout that explicitly reserves stock, reviews current
reservation prices, selects a saved delivery address and reserved-item shipping
service or available store pickup, and creates a pending order. Partial orders
require explicit consent. Profile/address editing reuses existing account screens
with a return link. Order cards open authenticated, owner-scoped details with
server snapshots and a manual status refresh. Payment and installments remain
explicitly unavailable. Pre-checkout shipping estimates
require configured shipping integration and variant measurements, do not
reserve stock and are invalidated when their inputs change.
Category filtering, storefront settings, sales dashboard and per-store branding
configuration are future work. Promotions remain unimplemented: no artificial
discount, crossed-out price, promotional modal or payment condition is shown.
A future promotion feature must supply authoritative original/effective prices
and validity before the UI displays those details automatically.
See `frontend/README.md` for local setup, proxy requirements and interface decisions.

The initial merchant interface is available on the store's local hostname and
verifies ownership before presenting administration. It supports initial
item-type and product management, variant creation, variant pricing, stock
entries, paginated stock history, photo preview/upload/removal/cover selection
and a paginated order list. Color and size suggestions
can be added per store. Order filters remain outside that interface. Sales
analytics, promotions, staff permissions and branding are not implemented.
Editable forms warn before discarding changes, and writes block internal
navigation until completion. Stock and price updates preserve other unsaved
variant fields. Product/variant breadcrumbs and a dedicated not-found page
complete navigation. The decisions and failure-recovery behavior are recorded
in `frontend/README.md` under "Decisões de acabamento".

### Mobile navigation, product actions and local favorites

At widths up to 720px, the storefront uses a sticky, compact header with the
brand, a left-hand menu trigger and accessible account/bag icons. The shared
`MobileMenuComponent` uses a native modal dialog for keyboard focus, Escape and
backdrop dismissal. The left menu includes "Início"; the mobile profile icon
opens a separate right-hand drawer directly, without navigating to settings first.
It lists customer or merchant sections and logout, or login/registration when
anonymous. Account and administration screens also open their menus on the right.
Both sides slide in/out over 260ms with a fading backdrop, respecting reduced
motion preferences. Its navigation closes after successful navigation, including
visits to the current URL; cancelled unsaved-change navigation keeps it open.
Customer and merchant settings reuse the desktop menu templates inside this
drawer and hide the desktop sidebar on mobile. Existing section URLs remain the
source of navigation: each section gets the mobile content area without creating
duplicate screens, forms or APIs. Desktop layout is preserved. Writes still
block navigation and menu dismissal. Categories and offers are explicitly
unavailable; mobile navigation does not simulate filters or promotional data.
Account and administration screens are lazy-loaded so their larger forms and
menus do not increase the initial storefront payload beyond its existing budget.
Section switches retain the same routed component and stable panel.
Quick add closes its native modal only after the cart write succeeds and the
shared busy lock is released; success feedback stays visible in the catalog.
Validation and API failures keep the selection open. Direct purchase still opens
bag review after confirmation.

`ProductCardComponent` centralizes the clickable product area, product information
and separate cart/favorite actions. The link opens product details to select and
buy; clicking either action does not activate the product link. The cart button
derives its state from `CartService`, not a per-card flag: any variant present
switches it to a dark-green "Retirar do carrinho" action. Removal requires a
native confirmation explaining that all colors, sizes and quantities of that
product will be removed. After confirmation the client reads the current bag and
sequentially removes that product's variants using existing endpoints, preserving
other products. Each confirmed response updates the shared bag. This sequence is
not atomic: failures stop it, warn about partial/uncertain removal and offer bag
review. A new attempt reads the server again; no writes are retried automatically.

`FavoritesService` stores only product IDs in origin-scoped `localStorage`.
Hearts toggle immediately and expose their state to assistive technology.
Favorites survive reloads and later visits in this browser; storage events keep
tabs of the same store in sync. They are not account-owned or synchronized across
devices. `/favoritos` is lazy-loaded and fetches current public details with at
most four concurrent requests. Missing/inactive products remain saved and are
distinguished from transient API failures; each offers retry/removal. Removal
does not touch the cart. Corrupt or blocked local storage is explicitly reported,
with recovery/confirmed clearing actions. No prices or product snapshots are
persisted. The product share icon sits
beside the description in the upper product summary, keeping the existing green
tooltip, copy feedback and manual-copy fallback.

### Frontend branch completion and next increments

The mobile purchase journey is exercised in Chromium against the production
build at 320px, 375px, 720px and 1280px with controlled API fixtures:
home, both drawers, favorite persistence, anonymous reload recovery, login/merge,
product, bag, checkout, pending order and account addresses. Checks include
horizontal overflow and uncaught browser errors. Shop headers remain sticky on
mobile; customer/merchant desktop layouts remain unchanged. These browser checks
verify frontend behavior, not a live payment or shipping integration. Backend
integration tests separately cover persisted recovery and merge authorization.

The current frontend finish includes mobile layout refinements, keyboard skip
navigation to main content, touch targets, inline field-error associations and
live cart-operation feedback. Product images have a reusable enlarged gallery
with native modal focus handling, arrow-key navigation, Escape dismissal and
explicit image-failure recovery. Store, login and product links can be copied
with permission-aware confirmation and a manual-copy alternative.

Unimplemented administration and account sections are grouped and labeled
"Em breve" instead of appearing as completed operations. Existing routes remain
available to explain limitations, without fake saves or sample production data.
These changes do not introduce backend behavior.

Integrated checkout now connects the existing reservation, profile, address,
shipping, pickup and pending-order APIs; no backend contract or payment behavior
was added. Checkout and order detail routes load lazily. Customer profile/address
types are shared instead of duplicating forms. Merely opening checkout does not
reserve stock. Login always opens the storefront; the bag is recovered so
customers can explicitly resume checkout after authentication.

The interface uses the server reservation prices and excludes unreserved items
from its subtotal. Local deadlines disable stale reservation/quote submission,
but the server remains authoritative. Address or fulfillment changes clear the
shipping selection. A changed shipping quote requires a new consultation and
explicit service selection, never silent acceptance of a new total. Unavailable
shipping/pickup cannot become free delivery or a simulated fulfillment option.

Cart and checkout requests share a busy lock. Cancellation requires a warning
about released stock and navigates to the bag only after server confirmation.
An explicit reservation-status refresh also reconciles lost cancellation
responses, clears stale shipping and never assumes that cancellation succeeded.
Transport/server failures during order creation retain the original reservation
and request body in page memory; editing/cancellation are hidden until the customer
explicitly retries the same idempotent request. Session expiry retains this attempt
for login, but closing/reloading still loses checkout choices and the exact
pending-request context, not the account cart. Explicit logout or a different
customer login clears prior checkout state. The
customer can recover created orders through the authenticated account order list.
After successful creation the bag remains tied to that active reservation; a new
checkout review is offered explicitly after its deadline, without hiding or
automatically cancelling the previous order.

Payment remains the next transactional increment and needs provider selection,
idempotent webhooks, expiration and atomic reservation-to-sale conversion.
Search/filter/sort must cover the whole server-side catalog,
not only a loaded page. Promotions need authoritative prices/validity, sales
dashboards need server-side aggregates and staff management needs server-enforced
permissions. A future customization preview may be prototyped separately with
clearly labeled demonstration data, but cannot claim to save or publish without
a backend contract. See `frontend/README.md` for the detailed interface decisions.

### Stable settings panels and future caching

Customer and merchant settings reuse the existing route component while changing
sections. Header, sidebar and panel remain mounted; headings render immediately
and only API-dependent contents are refreshed. A small positioned loading status,
`aria-busy`, inert updating content and a stable minimum panel height replace the
full-panel loading placeholder. Administrative pagination retains its records until
the response; section/filter changes remove incompatible records. This is rendering
continuity, not a new data cache, and each section still queries the server.

Future high-traffic-screen caching must start with request/latency measurements.
Deduplicate identical in-flight reads and cancel obsolete requests before adding
TTL-based reuse. Keys must include store/origin, resource, filters, sorting,
pagination and identity/permissions for authenticated reads. Invalidate affected
reads after writes and clear identity-specific state on logout/session expiration.
Never reuse responses between tenants or identities.

Evaluate short-lived catalog/settings caching and conditional ETag/Last-Modified
revalidation only with backend support and compatible cache headers. Respect
`Cache-Control: no-store`; profile, addresses and orders must not be persisted in
browser/service-worker caches without an explicit privacy and server policy.
Checkout stock/prices, reservations, shipping and order-creation results remain
server-authoritative; do not cache mutations or treat stale data as purchase
confirmation. Background refresh must label stale information and report failures.
No new caching layer is implemented in this increment.

### Local development infrastructure (planned)

Docker and Docker Compose are planned for running PostgreSQL, the backend and
the frontend together in development. This environment is not yet implemented;
database persistence, secrets, and preservation of the store hostname through
the frontend proxy must be addressed. Production deployment configuration is
separate work.

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

Registration, login and refresh are rate-limited per direct client IP and
category across all stores and application instances using PostgreSQL. The
default fixed window is 15 minutes from the first request: 10 registrations,
20 logins, and 60 refreshes. Both successful and unsuccessful requests count.
Merchant and customer endpoints share each category's quota; users behind the
same IP share it too. Logout is not limited. Exceeded requests return `429`,
`{"code":"AUTH_RATE_LIMITED"}`, `Retry-After` in seconds and `Cache-Control:
no-store`. If the rate-limit database is unavailable, protected requests fail
closed with `503` and `AUTH_RATE_LIMIT_UNAVAILABLE`.

Configure `AUTH_RATE_LIMIT_REGISTER`, `AUTH_RATE_LIMIT_LOGIN`,
`AUTH_RATE_LIMIT_REFRESH` and `AUTH_RATE_LIMIT_WINDOW_SECONDS` consistently on
every instance; all values must be positive, and the window must not exceed
86400 seconds. Expired IP records are removed in batches by a scheduled job.
IP addresses are temporarily stored in the database; account data and tokens
are not used as keys. The application uses the direct connection address and
ignores forwarded IP headers. A reverse proxy will therefore make clients
share the proxy's limit unless trusted client-IP handling is designed and
configured at the network boundary. Never trust headers supplied directly by
clients. Limits are not protection against distributed attacks and may require
tuning and additional edge-level abuse prevention before production.

#### Unified store login

The public `POST /api/public/store-auth/login` accepts `{"email":"owner@example.com",
"password":"..."}` on the store hostname and returns a flat
`{"accessToken":"...","refreshToken":"...","tokenType":"Bearer","expiresIn":900,
"accountKind":"merchant"}` response. `accountKind` is exactly `merchant` or
`customer`; successful responses and authentication/conflict errors are
`Cache-Control: no-store`. This endpoint shares the existing login rate limit.
Invalid input returns `400`, invalid credentials or an account absent from this
store returns `401`, and an unknown or unsupported hostname returns `404`.
The hostname is resolved by the existing storefront resolver; body store IDs
and untrusted forwarded-host headers do not select a tenant.

Identity is resolved by normalized email (strip whitespace and lowercase) and
store **before** checking a password. Only that store's merchant owner or a
customer registered in that store can authenticate. The existing merchant or
customer authentication service then verifies credentials and issues its
unchanged token pair. There is no password-based role probing or fallback.
If legacy data contains both identities for one email in the same store, login
returns `409` without checking either password or issuing tokens; support must
resolve that data explicitly, rather than silently merging accounts.

A store owner's email cannot also be registered as a customer in that store.
Customer registration and merchant onboarding check both account tables within
their transactions, acquiring a PostgreSQL `FOR UPDATE` lock on the store row
before the check and holding it through account creation. The existing unique
constraints additionally protect customer `(store_id, email)` and global
merchant email uniqueness. Onboarding creates a new store and its owner account
atomically, so a partially onboarded store is not visible to registration.
All supported account creation paths follow this locking protocol; future
email/ownership changes or account import paths must do so too. Direct SQL
imports bypassing the protocol need their own collision validation.
The same email remains allowed as a customer in other stores, including before
the person onboards their own store. Merchant email uniqueness remains global.
Collisions return the existing `409` account-email conflict; no global error
format or schema migration is introduced.

Existing login endpoints remain compatible. Merchant tokens use `/api/auth/refresh`
and `/api/auth/logout`; customer tokens use the host-scoped
`/api/public/customer-auth/refresh` and `/api/public/customer-auth/logout`.
These legacy JSON-token endpoints are not the Angular browser session flow.

#### Persistent browser sessions

The Angular application uses `POST /api/public/store-auth/session/login`,
`/register`, `/refresh` and `/logout` on the same origin as the storefront.
Login accepts `{email,password}`; register accepts `{name,email,password}` and
creates a signed-in customer. Refresh and logout need no request body. Login
and refresh return 200, registration 201 and logout 204.
Successful login/register/refresh return
`{accessToken,tokenType:"Bearer",expiresIn:900,accountKind,identity}`, where
`identity` is a stable account UUID and `accountKind` is `merchant` or `customer`.
There is no refresh token in JSON, URLs, frontend storage or JavaScript-readable
cookies. Session endpoints must not receive the access-token Authorization header.

The refresh cookie is `store_session`, host-only (no Domain), HttpOnly,
SameSite=Strict, with Path `/api/public/store-auth/session`. Secure is enabled by
default and always used for HTTPS requests. Every endpoint requires
`X-Store-Session: 1` and an Origin matching the request host, scheme and port
(default ports are equivalent). A supplied Sec-Fetch-Site must be same-origin.
Missing or mismatching origin/header, null origins and sibling subdomains return
403 `STORE_SESSION_ORIGIN_REJECTED` before authentication or rate limiting.
There is no permissive cross-origin credential flow.

`STORE_SESSION_COOKIE_SECURE` defaults to true: production requires HTTPS and
a reverse proxy preserving the browser Host. For **local HTTP only**, set
`STORE_SESSION_COOKIE_SECURE=false` on the backend and use the Angular same-origin
proxy with `changeOrigin:false`. Do not carry that override into production.
`STORE_SESSION_REFRESH_TTL` defaults to `P7D` and
`STORE_SESSION_ABSOLUTE_TTL` to `P30D`. The access JWT lasts 15 minutes; each
refresh rotates a 7-day token, capped by the original 30-day session deadline.
Cookie Max-Age respects both deadlines; renewal cannot extend the absolute limit.

Migration V25 stores browser session families and SHA-256 token hashes, never
raw refresh tokens. Refresh atomically locks the session/token, marks the old
token used and creates its replacement. Reusing an already-used token revokes
the whole family. Logout revokes the family even when its submitted cookie was
already rotated; concurrent rotation cannot undo logout. Issued access JWTs
remain valid until their short expiration, rather than being centrally revoked.
Each refresh validates the store, account existence and merchant ownership.
Another tenant's token is rejected without revoking that tenant's session.
Login/register/refresh reuse the existing authentication rate-limit buckets.

Missing, expired, revoked or replayed refresh cookies return 401
`STORE_SESSION_INVALID` and clear the cookie. Unknown stores return 404;
infrastructure errors retain their 5xx status, not a false unauthorized response.
The frontend waits for bootstrap restoration before rendering/guards, renews
early, deduplicates refresh within a tab, and uses Web Locks to serialize browser
session operations across tabs of the same origin when available. Browsers
without Web Locks retain per-tab deduplication; overlapping refreshes across tabs
can trigger replay protection and require login again. Refresh never emits a new
login for the same identity, so account-cart recovery and checkout are preserved.
Network/server restoration failures produce an explicit retry action; cart writes
remain blocked until session state is resolved.

### Catalog and planned inventory management

The item-type, product, manual inventory, inventory audit and checkout
reservation APIs described below are implemented. Automatic sale deductions,
management screens, promotions, and store-member permissions remain
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
be retrieved at `/{id}/variants/{variantId}`, have their optional price updated,
be deactivated, and be reactivated at
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

#### Product photos

The authenticated merchant manages photos with `POST /api/products/{id}/photos`
(multipart field `file`), `GET /api/products/{id}/photos`, `DELETE
/api/products/{id}/photos/{photoId}`, and `PUT /api/products/{id}/photos/order`
with `{"photoIds":["...","..."]}` containing every current photo exactly once.
The first photo is the cover. Each product has at most ten photos, each at most
10 MiB. JPEG, PNG and WebP content is decoded and validated; images above
40 million pixels are rejected. Photos belong to products, not variants.
Public catalog product responses include the ordered `photos` URL array.

Configure `PHOTO_S3_BUCKET`, `PHOTO_S3_REGION`, `PHOTO_PUBLIC_BASE_URL`,
and optionally `PHOTO_S3_ENDPOINT` for an S3-compatible service. Supply
credentials through the AWS SDK credential chain, not in the repository.
The bucket or CDN must be configured separately to serve the generated object
keys publicly; configure HTTPS and only expose intended product media. Public
URLs remain accessible to anyone who knows them, even if a product is
deactivated, and caches can retain images after deletion. Original image data,
including metadata, is public. Uploaded images are not resized or stripped.

Uploads and deletions use persistent intermediate states. Failed deletes and
interrupted uploads are retried by a background cleanup; uploads left pending
for 30 minutes are considered interrupted. Monitor cleanup failures and bucket
lifecycle/retention, including potentially orphaned objects after an uncertain
provider timeout. The S3-compatible service and CDN still need validation
in the target environment.

#### Shipping preparation

The merchant can configure one Brazilian address for both shipping origin and
pickup via authenticated `GET` and `PUT /api/store/shipping-settings`.
GET returns `404` until configured. PUT replaces the settings and accepts
`postalCode` (eight digits), `street`, `number`, optional `complement`,
`district`, `city`, `state` (a Brazilian UF), `pickupEnabled` (boolean) and
optional `pickupInstructions` (up to 1,000 characters). The address is
validated for format only, not verified externally. Management is scoped to
the authenticated merchant's store. When pickup is enabled, its address and
instructions are public through the pickup endpoint below.

After creating a variant, the merchant can set all its shipping measurements
with `PUT /api/products/{productId}/variants/{variantId}/shipping`, for example
`{"weightKg":0.125,"widthCm":10.25,"heightCm":3,"lengthCm":20}`.
All four values must be positive; weight supports up to three decimal places
and dimensions up to two. The variant response includes these values under
`shippingWeightKg`, `shippingWidthCm`, `shippingHeightCm` and
`shippingLengthCm`. Existing variants remain unchanged and have null
measurements until configured. Updates are recorded in product history.
Missing measurements prevent shipping quotes.

Pickup is free and uses the same address as the shipping origin.

The internal Melhor Envio quotation client is prepared for sandbox only.
It sends origin and destination postal codes and unit product data, rounding
each dimension up to whole centimeters without changing the catalog. It uses
the provider's customized price and delivery time and retains Correios
services. Provider failures and individual unavailable services are distinct.
**Pending sandbox validation:** Create a sandbox account and application,
configure the callback and credentials securely, and validate the OAuth
authorization, token renewal and quotation against the real sandbox. This
work is deferred until after this initial implementation is merged. The
quotation endpoints use the stored merchant authorization, but an end-to-end
quotation flow still needs real sandbox validation.
Simulated provider responses do not establish sandbox compatibility; do not
enable this integration for production before validating it and addressing
any provider-specific requirements.
Provider contract reference:
[Melhor Envio freight calculation](https://docs.melhorenvio.com.br/reference/calculo-de-fretes-por-produtos).

The sandbox OAuth API provides authenticated
`POST /api/integrations/melhor-envio/connect`, `GET /api/integrations/melhor-envio`
and `DELETE /api/integrations/melhor-envio`. Start returns an authorization URL
and sets a temporary HTTPS-only, HttpOnly, SameSite=Lax cookie. The browser
must open the URL and return to the same panel hostname. The callback is
`GET /api/public/integrations/melhor-envio/callback`, with a one-time state
valid for ten minutes and bound to the browser cookie and store. Only the
`shipping-calculate` scope is requested. Status is `DISCONNECTED`, `PENDING`,
`CONNECTED`, `REFRESHING` or `REAUTHORIZE`; an expired authorization attempt
is reported as `REAUTHORIZE`.

Configure `MELHOR_ENVIO_CLIENT_ID`, `MELHOR_ENVIO_CLIENT_SECRET`,
`MELHOR_ENVIO_USER_AGENT` (application name and technical contact email),
`MELHOR_ENVIO_REDIRECT_URI` (the exact HTTPS callback URL registered with the
provider), and `MELHOR_ENVIO_ENCRYPTION_KEY` (Base64-encoded random 32-byte
key) in the server's secret configuration. Keep secrets out of the repository,
logs and client responses. Keep the encryption key stable and back it up
securely: losing or changing it makes stored tokens unreadable. The callback
hostname must match the panel hostname that initiates the request. Configure
HTTPS end-to-end or explicitly configure a trusted reverse proxy so the
application recognizes HTTPS; forwarded headers must never be accepted from
untrusted clients.
Configure
reverse proxies and access logs not to record callback query parameters,
authorization codes, cookies or tokens; the callback sends no-store and
no-referrer headers.

Tokens are encrypted with AES-GCM and bound to the store. A refresh is claimed
before contacting the provider, preventing simultaneous reuse of the refresh
token; concurrent requests fail temporarily. If a refresh outcome is
uncertain, the store must reconnect rather than retrying a possibly consumed
token. An interrupted process can leave status `REFRESHING`; disconnect and
reconnect to recover. Disconnect removes local credentials but does not revoke
the application at Melhor Envio. Starting a new connection replaces any
existing local connection. Validation against the real provider remains
pending until the sandbox application is configured.

#### Shipping estimates

The public API uses the storefront hostname to select the store.
`POST /api/public/catalog/products/{productId}/variants/{variantId}/shipping-estimates`
accepts `{"postalCode":"96020360","quantity":2}`. `POST
/api/public/cart/shipping-estimates` accepts `{"postalCode":"96020360"}` and
requires the cart's `Cart-Token` header. Postal codes must have eight digits;
quantity must be a positive integer. No customer authentication is required.

The server obtains origin, current prices, dimensions, quantities and the
store's authorization itself. A successful response contains `quotes` and
`unavailable` arrays of Correios services. Each quote contains `id`, `name`,
`price` in BRL and `deliveryDays`; unavailable entries contain `id` and `name`.
Responses are `Cache-Control: no-store`. These are estimates, not guaranteed
prices or stock reservations. Values, stock, cart contents and settings can
change during or after a request. A cart with an active reservation can show
items as unavailable because its reserved stock is not available for new
reservations.

An empty cart or missing origin returns `409`. If any requested item is
inactive, has insufficient stock or lacks shipping measurements, no partial
quotation is sent: the `409` response contains `code: ITEMS_NOT_QUOTABLE` and
an `items` array with `productId`, `variantId` and `reasons` (`UNAVAILABLE`
and/or `MISSING_MEASUREMENTS`). Invalid input returns `400`; a foreign or
unknown product, variant or cart returns `404`. Integration errors, including
missing authorization, are reported as `503` with
`SHIPPING_TEMPORARILY_UNAVAILABLE`; they do not mean the service is free.

`POST /api/public/checkout/{reservationId}/shipping-estimates` accepts
`{"postalCode":"96020360"}` and requires the cart's `Cart-Token`, but not a
customer login. It quotes only fully reserved items, using reservation prices
and quantities and the current variant measurements and store origin. Items
that were not reserved are excluded; their status remains available through
the checkout API. An inactive, cancelled, expired or foreign reservation
returns `409`, including if it expires or is cancelled during the provider
call. A missing or foreign cart token returns `404`. The response adds
`quoteId`, `reservationId` and `expiresAt` to `quotes` and `unavailable`. Missing
measurements, origin and integration errors use the same responses described
above. Normal product and variant deactivation is blocked while active
reservations exist.

The server stores available checkout services under the quote ID, tied to the
store, reservation and destination postal code. The quote remains usable only
while the reservation is active. It is an estimate, not a guarantee of
provider price or availability, and is revalidated before order creation.
Quotes and reservations are retained; cleanup is not yet implemented. Existing
delivery orders may have no recorded shipping cost. The provider client targets
sandbox only; no real sandbox validation has been completed.

Cart creation, the three shipping estimate endpoints, and order creation are
rate-limited by direct client IP across stores and application instances. The
default fixed window is 15 minutes, with independent quotas of 20 cart creations,
60 shipping estimates shared by product, cart and checkout, and 30 order
creation attempts. All attempts count, including invalid requests, pickups and
idempotent order retries. Configure `PUBLIC_RATE_LIMIT_CART_CREATE`,
`PUBLIC_RATE_LIMIT_SHIPPING_QUOTE`, `PUBLIC_RATE_LIMIT_ORDER_CREATE` and
`PUBLIC_RATE_LIMIT_WINDOW_SECONDS` consistently on every instance; values must
be positive and the window at most 86400 seconds. Excess returns `429` with
`PUBLIC_RATE_LIMITED`, `Retry-After` and `Cache-Control: no-store`; database
errors fail closed with `503` and `PUBLIC_RATE_LIMIT_UNAVAILABLE`. Expired
IP records are cleaned periodically. Cart editing, checkout reservations and
other public operations are not covered. Direct IP and proxy limitations are
the same as for authentication: clients behind one IP share a quota, and
forwarded headers are not trusted. Distributed abuse and provider-wide
capacity still require additional protection before production.

#### Inventory

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

Checkout reservations expire or can be cancelled as described below.
Automatic deduction when a sale is completed is not yet implemented.

The authenticated `GET /api/inventory/products` lists product stock summaries,
filtered by product status (`active=true` by default, or `active=false`) and
paginated. `GET /api/inventory/products/{productId}` returns one summary,
including an inactive product. Each summary has SKU, name, description, product
status, `physicalQuantity` and `enabledQuantity`. Physical quantity includes
all variants, active or inactive. Enabled quantity includes only active
variants of an active product; it is zero when the product is inactive.
These are physical balances, not the quantity available for new reservations.

`GET /api/inventory/products/{productId}/variants` lists all variants of that
product with color, size, status, physical quantity, balance version and
enabled quantity. Variants with no recorded movement have quantity and version
zero. The list is paginated, with the same limits as other inventory lists.
All three endpoints enforce the authenticated store boundary. Totals and
variant pages are separate requests and can reflect different moments if stock
changes between requests.

The future management screen should show SKU, photo, description, total stock
and variant-level stock. The merchant product screen supports stock entries; audits
and stock exits remain API-only.

#### Public catalog API

The public, read-only catalog identifies a store from the request hostname:
`<store-subdomain>.<base-domain>`. Configure `STOREFRONT_BASE_DOMAIN` to the
platform's base domain; it defaults to `localhost` for development. For
example, when the server is listening on port 8080, replace `my-store` with a
registered store subdomain and run:

```shell
curl --resolve my-store.localhost:8080:127.0.0.1 \
  http://my-store.localhost:8080/api/public/catalog/products
```

Only a single valid store subdomain under the configured base domain is
accepted. The store is resolved in the database; clients do not provide a store
ID. The application ignores forwarded host headers by default
(`server.forward-headers-strategy=none`). A reverse proxy must preserve the
validated public Host, and deployments must not trust forwarded host headers
from untrusted clients. Configure the production base domain explicitly.

The following GET endpoints do not require authentication:

- `/api/public/catalog/products`: paginated product list.
- `/api/public/catalog/products/{productId}`: product name, description and
  default price.
- `/api/public/catalog/products/{productId}/variants`: paginated active
  variants, including color, nullable `colorHex`, size, effective price, quantity available for new reservations,
  and whether the quantity is greater than zero.

`colorHex` comes from a case-insensitive name match against that store's saved
COLOR option; options from another store never supply a swatch. Standard colors
use a shared frontend palette when no saved swatch exists. Unknown legacy colors
without a swatch use a neutral striped placeholder, not a guessed color.

Product and quick-add selectors load all variant pages before rendering separate
horizontal color-circle and size rows. Only distinct attributes present on this
product are offered, never the full administrative palette or default size list.
Color names remain accessible labels, without visible captions. Selecting either
attribute disables incompatible or sold-out choices in the other row, with a gray
overlay and X. Clicking a selected option again clears it to unlock alternatives.
Only a real in-stock variant matching both selections can be added or quoted.
Attributes absent from the product do not create fake color/size controls.
Visible controls are compact (32px, 24px swatches) with a thin soft-green selected
outline and a geometrically centered CSS cross. Touch hit areas extend to 44px
without enlarging their visible shape. Product-link copying uses a small accessible
link icon beside the description in the upper product summary, with a single custom hover/focus tooltip instead
of a large labeled button beside the purchase controls.

Only active products with at least one active variant are visible. Inactive
variants are omitted; active variants with zero stock remain visible and are
marked unavailable. A missing, inactive or different store's product returns
`404`. Prices are in BRL. Pagination uses zero-based `page`, with `size`
defaulting to 20 and limited to 100. Separate requests can reflect different
moments. Stock and prices are informational; checkout revalidates them, and
availability is not guaranteed until a reservation succeeds. Administrative
APIs, history and actor details remain private.

The public catalog API has no rate limiting. Production exposure requires an
abuse-prevention and traffic-limiting decision. Product details and checkout
screens consume these contracts; checkout remains authoritative for stock and price.

#### Anonymous shopping cart API

The public cart uses the same hostname-based store resolution as the catalog.
`POST /api/public/cart` creates an empty cart and returns a random `token` and
`expiresAt`. The token is returned only at creation; the client must keep it
securely on the device and send it in the `Cart-Token` header for subsequent
requests. It grants access to the cart, must not be put in a URL or logs, and
cannot be recovered if lost. Only its hash is stored by the server. Responses
are marked `Cache-Control: no-store`; a future storefront must implement
secure client-side handling and use HTTPS in production.

`GET /api/public/cart` returns the cart and its items. `PUT
/api/public/cart/items/{productId}/{variantId}` sets the desired quantity
using `{"quantity":2}`; retrying the same request does not add the quantity
again. `DELETE` on the same item path removes it, and deleting an absent item
is harmless. Each cart holds at most 100 distinct variants. Quantities must be
positive whole numbers within the stock currently available for new reservations; unavailable or
insufficient stock returns `409`, while an unknown variant or cart returns
`404`. A cart token cannot be used with another store's hostname. The cart
expires 30 days after creation or its last actual change; reads, unsuccessful
requests and unchanged retries do not renew it. Starting a checkout extends
cart expiration if needed until at least 30 days after the reservation expires.
Expired carts cannot be
accessed; automatic deletion of expired records is not yet implemented.

Items remain visible when product or variant status or stock changes. Each
item shows the current effective `price`, requested `quantity`, physical
`stockQuantity` (physical stock less active reservations) and `available` (active and enough available stock for the
requested quantity). Prices and stock can change immediately after a response;
the cart does not reserve stock or guarantee a purchase. This availability
also excludes the same cart's reservation; consult checkout for reserved items.
It contains no
customer data. Abuse prevention and rate limiting remain necessary before
production exposure, including protection against excessive cart creation.

#### Checkout reservations and planned live sales

Merchants may share storefront links during live broadcasts, with an expected
initial scenario of 50–100 people concurrently viewing the same items. The
future storefront should update stock visibility in near real time; WebSockets
are a possible approach, not yet selected or implemented. Client displays
may lag and must never be treated as an inventory guarantee.

Adding an item to the cart does not reserve it. On explicitly starting checkout, the
checkout atomically validates and reserves available stock on the server.
`POST /api/public/checkout` uses the store hostname and `Cart-Token` to start
checkout; it returns `ACTIVE`, an ID, expiration and an item list. Each item
includes the requested quantity, price at checkout, `reserved`, and the
available quantity observed when checkout began. An item is either fully
reserved or not reserved at all. If none can be reserved, the response is
`UNAVAILABLE`, with no ID or expiry, and the cart remains editable. An empty
cart returns `409`. A repeated start while active returns the same reservation
and deadline, including the original unavailable items.

Reservations expire 20 minutes after creation according to the database clock.
They cease consuming stock at expiration without a browser or scheduled job.
`GET /api/public/checkout` returns the latest checkout with status `ACTIVE`,
`EXPIRED`, or `CANCELLED`; an absent checkout returns `404`.
`POST /api/public/checkout/{reservationId}/cancel` releases that active reservation;
an outdated ID or no active reservation returns `409`. During an active reservation cart changes
return `409`; cancel first to modify it. Attempts to reduce physical stock below
active reservations or deactivate a reserved product or variant return `409`.
Expired and cancelled reservations are retained; cleanup is not implemented.
Prices in checkout are snapshots; creating a pending order is not a completed sale.

The checkout UI warns before cancellation that it releases the reserved
items and the store cannot guarantee their subsequent availability.
Competing requests are serialized against inventory and cannot reserve the
same units twice. Availability can change immediately after a response.

The checkout UI shows an expiration warning, disables order submission and
offers an explicit new stock/price review. It does not reload or automatically
return to a single product: a checkout may contain several products, and reloading
would lose the current delivery choices and pending-request context. This replaces the earlier planned
warning/OK/reload behavior. No local timer extends reservations. Payment processing,
order confirmation, sale deductions and WebSockets remain unimplemented.
Reservation duration configuration, cleanup and future
payment/order revalidation still need design before implementation.
No throughput guarantee is established for production traffic.

#### Pending orders

`POST /api/public/checkout/{reservationId}/orders` creates a pending order
for an active reservation. Use the store hostname, the customer's bearer
access token, and the `Cart-Token` header, with a JSON body such as:

```json
{"acceptPartial":false,"addressId":"00000000-0000-0000-0000-000000000001",
 "quoteId":"00000000-0000-0000-0000-000000000002","shippingServiceId":1}
```

The order records the authenticated customer's name, email and phone at
creation, together with a snapshot of the selected saved delivery address.
A customer-owned address is required for delivery. A profile phone is required
for either delivery or pickup. Existing orders without delivery details remain
readable. Omitting `fulfillmentType` retains the existing delivery behavior;
`"DELIVERY"` can also be supplied explicitly. New delivery orders require a
quote ID and a service ID from its available options. The saved address must
belong to the customer and its postal code must match the quotation. The server
looks up the confirmed price and deadline, recalculates the reserved items with
the provider, and does not trust client-supplied prices. If the chosen service is
unavailable or its price or delivery time changed, no order is created: `409`
returns `code: SHIPPING_QUOTE_CHANGED` and `quote` with a new quote ID, available
and unavailable services. The customer must choose again and resubmit. If the
address, origin or variant measurements change during the calculation, the
request returns `409` and requires a new quotation. Provider errors return
`503` with `SHIPPING_TEMPORARILY_UNAVAILABLE`; missing measurements return
`409` with `ITEMS_NOT_QUOTABLE`. A missing or invalid selection returns `409`.

The public `GET /api/public/pickup` uses the storefront hostname and returns
`available: false` when pickup is disabled or not configured. When enabled it
returns `available: true`, the store's `address` and optional `instructions`.
The response uses `Cache-Control: no-store`; availability and address can
change before order creation.

For pickup, send:

```json
{"acceptPartial":false,"fulfillmentType":"PICKUP"}
```

Do not supply `addressId`, `quoteId` or `shippingServiceId` for pickup. The server verifies that the store has
enabled pickup, otherwise returns `409`. A delivery request without an
`addressId`, a pickup request with one, or an unknown fulfillment type returns
`400`. The order preserves the pickup address, instructions and customer phone
as they were at creation. Its `pickup.cost` is zero, `delivery` is null and
`fulfillmentType` is `PICKUP`. Delivery orders have `fulfillmentType` set to
`DELIVERY` and `pickup` null. Both customer and merchant order details contain
the snapshot; their order lists include the fulfillment type. Historical orders
are treated as delivery orders, including legacy orders lacking delivery details.

If any checkout item was unavailable,
`acceptPartial` must explicitly be `true`; only fully reserved items enter the
order. Otherwise `false` is sufficient. The response is `200` with the order,
including the checkout price and quantity and a snapshot of product name and
variant attributes. For delivery, `delivery.shipping` snapshots the service ID,
name, price and delivery days. `total` is the sum of item prices and quantities
plus shipping in BRL; pickup adds no charge. Customer and merchant lists also
include shipping in `total`. Legacy deliveries without shipping details remain
readable and their totals remain item-only. Prices are captured from checkout;
names and variant attributes are captured at order creation.

There is at most one order per reservation. A repeat by the same customer with
the same `acceptPartial`, fulfillment type, `addressId`, quote ID and service ID (for delivery)
returns the original order, even if the address or pickup settings have since
changed, including after its expiration or cancellation and without another
provider call. Legacy delivery orders retain their original retry contract
without quote or service IDs. A different customer,
confirmation value or fulfillment selection, or
an inactive reservation without an order, returns `409`. No request extends the reservation. The order
is `PENDING` only while the reservation is active, becomes `EXPIRED` when the
deadline passes, and becomes `CANCELLED` when checkout is cancelled. Status is
derived from the reservation, so expiration and stock release do not require a
background job. A customer needs a new checkout after expiry.

`GET /api/public/customer/orders` and `GET /api/public/customer/orders/{orderId}`
require the store hostname and the owner's customer bearer token. The list
uses the same pagination limits as the merchant list.
`GET /api/public/orders/{orderId}` and the `Cart-Token` remain available only
for legacy anonymous orders, which are not linked to an account automatically.
`GET /api/orders` and `GET /api/orders/{orderId}` require a
merchant bearer token and are scoped to that merchant's store. The merchant
list accepts zero-based `page` and `size`, default 20 and maximum 100.
Responses containing customer information use `Cache-Control: no-store`.
For legacy orders, losing the cart token or cart expiration prevents customer
retrieval. The merchant retains access. The cart token still authorizes checkout
cancellation even after an order is created, so it remains a sensitive secret.
Orders and personal data are retained without automatic deletion;
retention, privacy policy and secure customer recovery require future design.

This block does not implement shipping calculation, order confirmation, payment
processing or physical stock deduction.
A payment provider (such as PagBank or Mercado Pago) has not been selected.
Payment data handling, provider integration, payment
expiration, asynchronous confirmation, secure webhooks, idempotency, and
atomic reservation-to-sale conversion require a separate design. Do not store
card numbers or security codes in this application. Without those capabilities,
pending orders cannot be paid or fulfilled.

#### Planned fulfillment status

Only confirmed payment should start fulfillment. The intended flow records
payment confirmation automatically and then moves the order to preparing.
For shipped orders, the merchant manually marks the order as shipped; a
future Correios tracking integration should update it to delivered when a
confirmed delivery event is received. The provider, access conditions, event
mapping, authentication and handling of delayed, duplicated, missing or
exception events still require design and validation. Automatic delivery
updates are not implemented.

For store pickup, the planned sequence is preparing, ready for pickup, and
picked up. The merchant manually advances it after payment confirmation.
These are future fulfillment states, distinct from the current reservation-
derived pending, expired and cancelled order status. No payment confirmation,
manual fulfillment transitions or tracking integration is available yet.

#### Customer accounts

Each customer account belongs to a single store. The same email may be used
independently in different stores, while email uniqueness within a store is
case-insensitive. All customer authentication endpoints resolve the store
from the hostname and use HTTPS in production:

- `POST /api/public/customer-auth/register`: `name`, `email`, and `password`
  (12–128 characters); returns `201` or `409` for a duplicate email.
- `POST /api/public/customer-auth/login`: `email` and `password`; returns an
  access token and a refresh token.
- `POST /api/public/customer-auth/refresh`: `refreshToken`; rotates the refresh
  token atomically. A token cannot be refreshed on another store.
- `POST /api/public/customer-auth/logout`: `refreshToken`; revokes it. An
  already-issued access token remains valid until expiration.

Customer access tokens last 15 minutes; refresh tokens last seven days and
are stored hashed. Customer and merchant tokens have distinct authorization:
customer tokens cannot access merchant endpoints, and merchant tokens cannot
act as customer tokens. The cart and checkout may still be started
anonymously, but creating a new order requires customer login. Existing
anonymous orders are not associated merely by matching email.

Email verification, password recovery, account management, secure recovery of
legacy orders, broader abuse prevention and privacy/retention policies
remain to be designed before production. Registration currently permits
unverified email addresses. Do not treat possession of an account as proof
that its email address is controlled by the account owner.

#### Customer profile and delivery addresses

The authenticated customer can read `GET /api/public/customer/profile` and
update `PUT /api/public/customer/profile` with `{"name":"Ana",
"phone":"11987654321"}`. Phone must contain 10 or 11 digits (Brazilian area
code and number); no external verification is performed. Email changes and
CPF are not implemented. Existing accounts may have a null phone, but need
to provide one before creating a new order.

`/api/public/customer/addresses` supports `GET` and `POST`; the
`/{addressId}` path supports `GET`, `PUT`, and `DELETE`. Each customer can
have at most 10 addresses per store. The response includes `country: "BR"`;
only Brazil is supported. For example:

```json
{
  "recipient": "Ana",
  "postalCode": "01001000",
  "street": "Rua A",
  "number": "10",
  "complement": "Apto 2",
  "district": "Centro",
  "city": "São Paulo",
  "state": "SP"
}
```

Recipient, street, number, district and city are required; complement is
optional. CEP must be eight digits and state must be a valid uppercase
Brazilian UF. Validation checks format only, not the existence or
deliverability of an address. These endpoints require the store hostname and
the customer's bearer token, are isolated by store and account, and return
`Cache-Control: no-store`. Editing or deleting a saved address does not change
the copy on an existing order. Delivery snapshots and phone are available to
the order owner and merchant, and are subject to the same pending privacy and
retention decisions as order data. Freight, address verification, customer
UI and payment integration remain future work.

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