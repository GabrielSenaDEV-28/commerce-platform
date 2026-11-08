package com.gabrielsena.commerce.order.application;

import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
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

    public OrderService(JdbcTemplate jdbc, ShoppingCartService carts, CatalogStoreResolver stores) {
        this.jdbc = jdbc;
        this.carts = carts;
        this.stores = stores;
    }

    public record OrderItem(UUID productId, UUID variantId, String name, String color, String size,
                            BigDecimal unitPrice, int quantity) { }
    public record Order(UUID id, UUID reservationId, String customerName, String customerEmail,
                        String status, Instant createdAt, Instant expiresAt, BigDecimal total,
                        List<OrderItem> items) { }
    public record OrderSummary(UUID id, String customerName, String customerEmail, String status,
                               Instant createdAt, Instant expiresAt, BigDecimal total) { }
    private record OrderRow(UUID id, UUID reservationId, String name, String email, boolean acceptedPartial,
                            String status, Instant createdAt, Instant expiresAt) { }

    private static final String STATUS = """
            CASE WHEN r.cancelled_at IS NOT NULL THEN 'CANCELLED'
                 WHEN r.expires_at <= statement_timestamp() THEN 'EXPIRED'
                 ELSE 'PENDING' END
            """;

    @Transactional
    public Order create(UUID store, String token, UUID reservationId, String customerName, String customerEmail,
                        boolean acceptPartial) {
        UUID cart = carts.cartId(store, token, true);
        String name = customerName.strip();
        String email = customerEmail.strip();
        List<OrderRow> existing = rows("""
                WHERE o.store_id = ? AND r.cart_id = ? AND o.reservation_id = ?
                """, store, cart, reservationId);
        if (!existing.isEmpty()) {
            OrderRow order = existing.getFirst();
            if (!order.name().equals(name) || !order.email().equals(email)
                    || order.acceptedPartial() != acceptPartial) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order request differs from the original.");
            }
            return order(store, order);
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
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO commerce_orders (order_id, store_id, reservation_id, customer_name, customer_email,
                    accepted_partial, created_at)
                SELECT ?, store_id, reservation_id, ?, ?, ?, clock_timestamp()
                FROM checkout_reservations WHERE store_id = ? AND cart_id = ? AND reservation_id = ?
                  AND cancelled_at IS NULL AND expires_at > clock_timestamp()
                """, id, name, email, acceptPartial, store, cart, reservationId);
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout reservation is not active.");
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
        return order(store, find(store, id, carts.cartId(store, token, false)));
    }

    @Transactional(readOnly = true)
    public Order merchantOrder(UUID actor, UUID id) {
        UUID store = stores.storeFor(actor);
        return order(store, find(store, id, null));
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> merchantOrders(UUID actor, Pageable page) {
        UUID store = stores.storeFor(actor);
        long count = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_orders WHERE store_id = ?", Long.class, store));
        List<OrderSummary> result = jdbc.query("""
                SELECT o.order_id, o.customer_name, o.customer_email, %s AS status, o.created_at, r.expires_at,
                    (SELECT SUM(i.unit_price * i.quantity) FROM commerce_order_items i
                     WHERE i.store_id = o.store_id AND i.order_id = o.order_id) AS total
                FROM commerce_orders o JOIN checkout_reservations r
                  ON r.store_id = o.store_id AND r.reservation_id = o.reservation_id
                WHERE o.store_id = ? ORDER BY o.created_at DESC, o.order_id DESC LIMIT ? OFFSET ?
                """.formatted(STATUS), (rs, row) -> new OrderSummary(rs.getObject("order_id", UUID.class),
                rs.getString("customer_name"), rs.getString("customer_email"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getBigDecimal("total")), store, page.getPageSize(), page.getOffset());
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
                SELECT o.order_id, o.reservation_id, o.customer_name, o.customer_email, o.accepted_partial,
                       %s AS status, o.created_at, r.expires_at
                FROM commerce_orders o JOIN checkout_reservations r
                  ON r.store_id = o.store_id AND r.reservation_id = o.reservation_id
                """.formatted(STATUS) + where, (rs, row) -> new OrderRow(rs.getObject("order_id", UUID.class),
                rs.getObject("reservation_id", UUID.class), rs.getString("customer_name"),
                rs.getString("customer_email"), rs.getBoolean("accepted_partial"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), args);
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
        return new Order(row.id(), row.reservationId(), row.name(), row.email(), row.status(),
                row.createdAt(), row.expiresAt(), total, items);
    }
}
