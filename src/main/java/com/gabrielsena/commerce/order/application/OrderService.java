package com.gabrielsena.commerce.order.application;

import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.customer.application.CustomerAuthService.Customer;
import com.gabrielsena.commerce.customer.application.CustomerProfileService;
import com.gabrielsena.commerce.customer.application.CustomerProfileService.Address;
import com.gabrielsena.commerce.shipping.application.ShippingEstimateService;
import com.gabrielsena.commerce.shipping.application.ShippingEstimateService.CheckoutQuote;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioClient.ServiceQuote;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService.PickupAddress;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService.PickupOption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    private final ShoppingCartService carts;
    private final CatalogStoreResolver stores;
    private final CustomerProfileService profiles;
    private final StoreShippingSettingsService shippingSettings;
    private final ShippingEstimateService estimates;

    public OrderService(JdbcTemplate jdbc, ShoppingCartService carts, CatalogStoreResolver stores,
                        CustomerProfileService profiles, StoreShippingSettingsService shippingSettings,
                        ShippingEstimateService estimates) {
        this.jdbc = jdbc;
        this.carts = carts;
        this.stores = stores;
        this.profiles = profiles;
        this.shippingSettings = shippingSettings;
        this.estimates = estimates;
    }

    public enum FulfillmentType { DELIVERY, PICKUP }
    public record OrderItem(UUID productId, UUID variantId, String name, String color, String size,
                            BigDecimal unitPrice, int quantity) { }
    public record Order(UUID id, UUID reservationId, String customerName, String customerEmail,
                        String status, Instant createdAt, Instant expiresAt, BigDecimal total,
                        List<OrderItem> items, Delivery delivery, FulfillmentType fulfillmentType, Pickup pickup) { }
    public record Shipping(int serviceId, String name, BigDecimal price, int deliveryDays) { }
    public record Delivery(String phone, Address address, Shipping shipping) { }
    public record Pickup(String phone, PickupAddress address, String instructions, BigDecimal cost) { }
    public record OrderSummary(UUID id, String customerName, String customerEmail, String status,
                               Instant createdAt, Instant expiresAt, BigDecimal total, FulfillmentType fulfillmentType) { }
    private record OrderRow(UUID id, UUID reservationId, UUID customerId, String name, String email, boolean acceptedPartial,
                            UUID addressId, FulfillmentType fulfillmentType, String status, Instant createdAt, Instant expiresAt,
                            UUID quoteId, Integer serviceId) { }

    private static final String STATUS = """
            CASE WHEN r.cancelled_at IS NOT NULL THEN 'CANCELLED'
                 WHEN r.expires_at <= statement_timestamp() THEN 'EXPIRED'
                 ELSE 'PENDING' END
            """;

    @Transactional
    public Order create(UUID store, String token, UUID reservationId, Customer customer, boolean acceptPartial, UUID addressId) {
        return create(store, token, reservationId, customer, acceptPartial, addressId, FulfillmentType.DELIVERY);
    }

    @Transactional
    public Order create(UUID store, String token, UUID reservationId, Customer customer, boolean acceptPartial,
                        UUID addressId, FulfillmentType fulfillmentType) {
        return createInternal(store, token, reservationId, customer, acceptPartial, addressId, fulfillmentType, null, null, null);
    }

    @Transactional(readOnly = true)
    public Order existing(UUID store, String token, UUID reservationId, Customer customer, boolean acceptPartial,
                          UUID addressId, FulfillmentType fulfillmentType, UUID quoteId, Integer serviceId) {
        UUID cart = carts.cartId(store, token, false);
        return existing(store, cart, reservationId, customer, acceptPartial, addressId, fulfillmentType, quoteId, serviceId);
    }

    @Transactional
    public Order createWithShipping(UUID store, String token, UUID reservationId, Customer customer, boolean acceptPartial,
                                    UUID addressId, UUID quoteId, int serviceId, CheckoutQuote recalculated) {
        return createInternal(store, token, reservationId, customer, acceptPartial, addressId,
                FulfillmentType.DELIVERY, quoteId, serviceId, recalculated);
    }

    private Order existing(UUID store, UUID cart, UUID reservationId, Customer customer, boolean acceptPartial,
                           UUID addressId, FulfillmentType fulfillmentType, UUID quoteId, Integer serviceId) {
        List<OrderRow> existing = rows("""
                WHERE o.store_id = ? AND r.cart_id = ? AND o.reservation_id = ?
                """, store, cart, reservationId);
        if (existing.isEmpty()) {
            return null;
        }
        OrderRow order = existing.getFirst();
        if (!customer.id().equals(order.customerId()) || order.acceptedPartial() != acceptPartial
                || !Objects.equals(order.addressId(), addressId) || order.fulfillmentType() != fulfillmentType
                || !Objects.equals(order.quoteId(), quoteId) || !Objects.equals(order.serviceId(), serviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order request differs from the original.");
        }
        return order(store, order);
    }

    private Order createInternal(UUID store, String token, UUID reservationId, Customer customer, boolean acceptPartial,
                                 UUID addressId, FulfillmentType fulfillmentType, UUID quoteId, Integer serviceId,
                                 CheckoutQuote recalculated) {
        if (fulfillmentType == null || (fulfillmentType == FulfillmentType.PICKUP && addressId != null)
                || (fulfillmentType == FulfillmentType.DELIVERY && addressId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fulfillment details.");
        }
        UUID cart = carts.cartId(store, token, true);
        Order existing = existing(store, cart, reservationId, customer, acceptPartial, addressId, fulfillmentType, quoteId, serviceId);
        if (existing != null) {
            return existing;
        }
        if (fulfillmentType == FulfillmentType.DELIVERY && (quoteId == null || serviceId == null || recalculated == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping selection is required.");
        }

        List<Boolean> items = jdbc.query("""
                SELECT i.reserved FROM checkout_reservation_items i
                JOIN checkout_reservations r ON r.store_id = i.store_id AND r.reservation_id = i.reservation_id
                WHERE r.store_id = ? AND r.cart_id = ? AND r.reservation_id = ?
                  AND r.cancelled_at IS NULL AND r.expires_at > clock_timestamp()
                """, (rs, row) -> rs.getBoolean(1), store, cart, reservationId);
        if (items.isEmpty() || items.stream().noneMatch(Boolean::booleanValue)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout reservation is not active.");
        }
        if (items.contains(false) && !acceptPartial) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Partial checkout requires explicit confirmation.");
        }
        var profile = profiles.lockCustomer(store, customer.id());
        if (profile.phone() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A customer phone is required.");
        }
        Address address = fulfillmentType == FulfillmentType.DELIVERY ? profiles.get(store, customer.id(), addressId) : null;
        PickupOption pickup = fulfillmentType == FulfillmentType.PICKUP ? shippingSettings.lockAvailablePickup(store) : null;
        ServiceQuote selected = null;
        if (address != null) {
            var original = estimates.selection(store, reservationId, quoteId, serviceId);
            if (!address.postalCode().equals(original.postalCode())
                    || !address.postalCode().equals(recalculated.request().destinationPostalCode())
                    || !reservationId.equals(recalculated.reservationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping destination changed; quote again.");
            }
            estimates.verifyQuoteInputs(store, cart, recalculated);
            selected = recalculated.quotes().stream().filter(quote -> quote.id() == serviceId).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Shipping service is unavailable."));
            if (selected.price().compareTo(original.service().price()) != 0
                    || selected.deliveryDays() != original.service().deliveryDays()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping quote changed.");
            }
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO commerce_orders (order_id, store_id, reservation_id, customer_id, customer_name, customer_email,
                    accepted_partial, fulfillment_type, created_at)
                SELECT ?, store_id, reservation_id, ?, ?, ?, ?, ?, clock_timestamp()
                FROM checkout_reservations WHERE store_id = ? AND cart_id = ? AND reservation_id = ?
                  AND cancelled_at IS NULL AND expires_at > clock_timestamp()
                """, id, customer.id(), profile.name(), profile.email(), acceptPartial, fulfillmentType.name(), store, cart, reservationId);
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout reservation is not active.");
        }
        if (address != null) {
            jdbc.update("""
                INSERT INTO order_delivery_details (store_id, order_id, address_id, phone, recipient, postal_code,
                    street, house_number, complement, district, city, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, store, id, address.id(), profile.phone(), address.recipient(), address.postalCode(),
                address.street(), address.number(), address.complement(), address.district(), address.city(), address.state());
            jdbc.update("""
                INSERT INTO order_shipping_details (store_id, order_id, quote_id, service_id, service_name, price, delivery_days)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, store, id, quoteId, selected.id(), selected.name(), selected.price(), selected.deliveryDays());
        } else {
            PickupAddress location = pickup.address();
            jdbc.update("""
                    INSERT INTO order_pickup_details (store_id, order_id, phone, postal_code, street, house_number,
                        complement, district, city, state, instructions)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, store, id, profile.phone(), location.postalCode(), location.street(), location.number(),
                    location.complement(), location.district(), location.city(), location.state(), pickup.instructions());
        }
        jdbc.update("""
                INSERT INTO commerce_order_items (store_id, order_id, product_id, variant_id, product_name,
                    color, size, unit_price, quantity)
                SELECT i.store_id, ?, i.product_id, i.variant_id, p.name, v.color, v.size, i.price, i.quantity
                FROM checkout_reservation_items i
                JOIN catalog_products p ON p.store_id = i.store_id AND p.product_id = i.product_id
                JOIN catalog_product_variants v ON v.store_id = i.store_id AND v.product_id = i.product_id
                    AND v.variant_id = i.variant_id
                WHERE i.store_id = ? AND i.reservation_id = ? AND i.reserved
                """, id, store, reservationId);
        return order(store, find(store, id, cart));
    }

    @Transactional(readOnly = true)
    public Order customerOrder(UUID store, String token, UUID id) {
        List<OrderRow> result = rows("""
                WHERE o.store_id = ? AND o.order_id = ? AND r.cart_id = ? AND o.customer_id IS NULL
                """, store, id, carts.cartId(store, token, false));
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }
        return order(store, result.getFirst());
    }

    @Transactional(readOnly = true)
    public Order ownedOrder(UUID store, UUID customer, UUID id) {
        List<OrderRow> result = rows("WHERE o.store_id = ? AND o.order_id = ? AND o.customer_id = ?", store, id, customer);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }
        return order(store, result.getFirst());
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> customerOrders(UUID store, UUID customer, Pageable page) {
        return summaries(store, customer, page);
    }

    @Transactional(readOnly = true)
    public Order merchantOrder(UUID actor, UUID id) {
        UUID store = stores.storeFor(actor);
        return order(store, find(store, id, null));
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> merchantOrders(UUID actor, Pageable page) {
        return summaries(stores.storeFor(actor), null, page);
    }

    private Page<OrderSummary> summaries(UUID store, UUID customer, Pageable page) {
        String customerFilter = customer == null ? "" : " AND o.customer_id = ?";
        Object[] countArgs = customer == null ? new Object[]{store} : new Object[]{store, customer};
        long count = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_orders o WHERE o.store_id = ?" + customerFilter, Long.class, countArgs));
        Object[] queryArgs = customer == null
                ? new Object[]{store, page.getPageSize(), page.getOffset()}
                : new Object[]{store, customer, page.getPageSize(), page.getOffset()};
        List<OrderSummary> result = jdbc.query("""
                SELECT o.order_id, o.customer_name, o.customer_email, o.fulfillment_type, %s AS status, o.created_at, r.expires_at,
                    (SELECT SUM(i.unit_price * i.quantity) FROM commerce_order_items i
                     WHERE i.store_id = o.store_id AND i.order_id = o.order_id)
                     + COALESCE(s.price, 0) AS total
                FROM commerce_orders o JOIN checkout_reservations r
                  ON r.store_id = o.store_id AND r.reservation_id = o.reservation_id
                LEFT JOIN order_shipping_details s ON s.store_id = o.store_id AND s.order_id = o.order_id
                WHERE o.store_id = ?
                """.formatted(STATUS) + customerFilter + " ORDER BY o.created_at DESC, o.order_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new OrderSummary(rs.getObject("order_id", UUID.class),
                rs.getString("customer_name"), rs.getString("customer_email"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getBigDecimal("total"), FulfillmentType.valueOf(rs.getString("fulfillment_type"))), queryArgs);
        return new PageImpl<>(result, page, count);
    }

    private OrderRow find(UUID store, UUID id, UUID cart) {
        List<OrderRow> result = cart == null
                ? rows("WHERE o.store_id = ? AND o.order_id = ?", store, id)
                : rows("WHERE o.store_id = ? AND o.order_id = ? AND r.cart_id = ?", store, id, cart);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }
        return result.getFirst();
    }

    private List<OrderRow> rows(String where, Object... args) {
        return jdbc.query("""
                SELECT o.order_id, o.reservation_id, o.customer_id, o.customer_name, o.customer_email, o.accepted_partial, d.address_id, o.fulfillment_type,
                       s.quote_id, s.service_id,
                       %s AS status, o.created_at, r.expires_at
                FROM commerce_orders o JOIN checkout_reservations r
                  ON r.store_id = o.store_id AND r.reservation_id = o.reservation_id
                LEFT JOIN order_delivery_details d ON d.store_id = o.store_id AND d.order_id = o.order_id
                LEFT JOIN order_shipping_details s ON s.store_id = o.store_id AND s.order_id = o.order_id
                """.formatted(STATUS) + where, (rs, row) -> new OrderRow(rs.getObject("order_id", UUID.class),
                rs.getObject("reservation_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("customer_email"), rs.getBoolean("accepted_partial"), rs.getObject("address_id", UUID.class),
                FulfillmentType.valueOf(rs.getString("fulfillment_type")), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("quote_id", UUID.class), rs.getObject("service_id", Integer.class)), args);
    }

    private Order order(UUID store, OrderRow row) {
        List<OrderItem> items = jdbc.query("""
                SELECT product_id, variant_id, product_name, color, size, unit_price, quantity
                FROM commerce_order_items WHERE store_id = ? AND order_id = ? ORDER BY product_id, variant_id
                """, (rs, number) -> new OrderItem(rs.getObject("product_id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getString("product_name"), rs.getString("color"),
                rs.getString("size"), rs.getBigDecimal("unit_price"), rs.getInt("quantity")), store, row.id());
        BigDecimal total = items.stream().map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Delivery> details = jdbc.query("""
                SELECT d.*, s.service_id, s.service_name, s.price AS shipping_price, s.delivery_days
                FROM order_delivery_details d LEFT JOIN order_shipping_details s
                  ON s.store_id = d.store_id AND s.order_id = d.order_id
                WHERE d.store_id = ? AND d.order_id = ?
                """, (rs, number) -> new Delivery(rs.getString("phone"),
                new Address(rs.getObject("address_id", UUID.class), rs.getString("recipient"),
                        rs.getString("postal_code"), rs.getString("street"), rs.getString("house_number"),
                        rs.getString("complement"), rs.getString("district"), rs.getString("city"),
                        rs.getString("state"), "BR"), rs.getObject("service_id", Integer.class) == null ? null :
                new Shipping(rs.getInt("service_id"), rs.getString("service_name"),
                        rs.getBigDecimal("shipping_price"), rs.getInt("delivery_days"))), store, row.id());
        if (!details.isEmpty() && details.getFirst().shipping() != null) {
            total = total.add(details.getFirst().shipping().price());
        }
        List<Pickup> pickups = jdbc.query("""
                SELECT * FROM order_pickup_details WHERE store_id = ? AND order_id = ?
                """, (rs, number) -> new Pickup(rs.getString("phone"),
                new PickupAddress(rs.getString("postal_code"), rs.getString("street"), rs.getString("house_number"),
                        rs.getString("complement"), rs.getString("district"), rs.getString("city"),
                        rs.getString("state"), "BR"), rs.getString("instructions"), new BigDecimal("0.00")),
                store, row.id());
        return new Order(row.id(), row.reservationId(), row.name(), row.email(), row.status(),
                row.createdAt(), row.expiresAt(), total, items, details.isEmpty() ? null : details.getFirst(),
                row.fulfillmentType(), pickups.isEmpty() ? null : pickups.getFirst());
    }
}
