package com.gabrielsena.commerce.shipping.application;

import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioClient;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioOAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ShippingEstimateService {
    private final JdbcTemplate jdbc;
    private final ShoppingCartService carts;
    private final MelhorEnvioOAuthService authorization;
    private final MelhorEnvioClient provider;

    public ShippingEstimateService(JdbcTemplate jdbc, ShoppingCartService carts,
                                   MelhorEnvioOAuthService authorization, MelhorEnvioClient provider) {
        this.jdbc = jdbc;
        this.carts = carts;
        this.authorization = authorization;
        this.provider = provider;
    }

    public record ItemIssue(UUID productId, UUID variantId, List<String> reasons) { }
    public static class InvalidItems extends RuntimeException {
        private final List<ItemIssue> items;
        public InvalidItems(List<ItemIssue> items) {
            super("Items cannot be quoted.");
            this.items = List.copyOf(items);
        }
        public List<ItemIssue> items() { return items; }
    }
    private record Item(UUID productId, UUID variantId, int quantity, BigDecimal price,
                        BigDecimal weight, BigDecimal width, BigDecimal height, BigDecimal length,
                        boolean available) { }
    public record CheckoutQuote(UUID reservationId, Instant expiresAt,
                                List<MelhorEnvioClient.ServiceQuote> quotes,
                                List<MelhorEnvioClient.UnavailableService> unavailable) { }

    private static final String ITEM_COLUMNS = """
            p.product_id, v.variant_id, COALESCE(v.price, p.price) AS price,
            v.shipping_weight_kg, v.shipping_width_cm, v.shipping_height_cm, v.shipping_length_cm,
            p.active AND v.active AS active, s.quantity AS stock
            """;
    private static final String ITEM_JOINS = """
            JOIN catalog_products p ON p.store_id = v.store_id AND p.product_id = v.product_id
            JOIN inventory_available_stock s ON s.store_id = v.store_id AND s.product_id = v.product_id
                AND s.variant_id = v.variant_id
            """;

    public MelhorEnvioClient.QuoteResult product(UUID store, UUID product, UUID variant,
                                                 int quantity, String destination) {
        List<Item> items = jdbc.query("SELECT " + ITEM_COLUMNS + """
                FROM catalog_product_variants v
                """ + ITEM_JOINS + """
                WHERE v.store_id = ? AND v.product_id = ? AND v.variant_id = ?
                """, (rs, row) -> item(rs, quantity), store, product, variant);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product or variant not found.");
        }
        return estimate(store, destination, items);
    }

    public MelhorEnvioClient.QuoteResult cart(UUID store, String token, String destination) {
        UUID cartId = carts.cartId(store, token, false);
        List<Item> items = jdbc.query("SELECT " + ITEM_COLUMNS + """
                , i.quantity AS requested_quantity
                FROM shopping_cart_items i
                JOIN catalog_product_variants v ON v.store_id = i.store_id
                    AND v.product_id = i.product_id AND v.variant_id = i.variant_id
                """ + ITEM_JOINS + """
                WHERE i.store_id = ? AND i.cart_id = ? ORDER BY i.product_id, i.variant_id
                """, (rs, row) -> item(rs, rs.getInt("requested_quantity")), store, cartId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The cart is empty.");
        }
        return estimate(store, destination, items);
    }

    public CheckoutQuote checkout(UUID store, String token, UUID reservationId, String destination) {
        UUID cartId = carts.cartId(store, token, false);
        activeReservation(store, cartId, reservationId);
        List<Item> items = jdbc.query("""
                SELECT i.product_id, i.variant_id, i.quantity, i.price,
                       v.shipping_weight_kg, v.shipping_width_cm, v.shipping_height_cm, v.shipping_length_cm
                FROM checkout_reservation_items i
                JOIN checkout_reservations r ON r.store_id = i.store_id AND r.reservation_id = i.reservation_id
                JOIN catalog_product_variants v ON v.store_id = i.store_id AND v.product_id = i.product_id
                    AND v.variant_id = i.variant_id
                WHERE r.store_id = ? AND r.cart_id = ? AND r.reservation_id = ?
                    AND r.cancelled_at IS NULL AND r.expires_at > clock_timestamp() AND i.reserved
                ORDER BY i.product_id, i.variant_id
                """, (rs, row) -> item(rs, rs.getInt("quantity"), true), store, cartId, reservationId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout reservation is not active.");
        }
        MelhorEnvioClient.QuoteResult result = estimate(store, destination, items);
        Instant expiresAt = activeReservation(store, cartId, reservationId);
        return new CheckoutQuote(reservationId, expiresAt, result.quotes(), result.unavailable());
    }

    private Instant activeReservation(UUID store, UUID cartId, UUID reservationId) {
        return jdbc.query("""
                SELECT expires_at FROM checkout_reservations
                WHERE store_id = ? AND cart_id = ? AND reservation_id = ?
                    AND cancelled_at IS NULL AND expires_at > clock_timestamp()
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), store, cartId, reservationId).stream()
                .findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.CONFLICT, "Checkout reservation is not active."));
    }

    private MelhorEnvioClient.QuoteResult estimate(UUID store, String destination, List<Item> items) {
        List<ItemIssue> issues = new ArrayList<>();
        List<MelhorEnvioClient.Product> products = new ArrayList<>();
        for (Item item : items) {
            List<String> reasons = new ArrayList<>();
            if (!item.available()) {
                reasons.add("UNAVAILABLE");
            }
            if (item.weight() == null || item.width() == null || item.height() == null || item.length() == null) {
                reasons.add("MISSING_MEASUREMENTS");
            }
            if (!reasons.isEmpty()) {
                issues.add(new ItemIssue(item.productId(), item.variantId(), List.copyOf(reasons)));
            } else {
                products.add(new MelhorEnvioClient.Product(item.variantId().toString(), item.width(), item.height(),
                        item.length(), item.weight(), item.price(), item.quantity()));
            }
        }
        if (!issues.isEmpty()) {
            throw new InvalidItems(issues);
        }
        String origin = jdbc.query("SELECT postal_code FROM store_shipping_settings WHERE store_id = ?",
                (rs, row) -> rs.getString(1), store).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Shipping origin is not configured."));
        String token = authorization.accessToken(store);
        return provider.quote(token, new MelhorEnvioClient.QuoteRequest(origin, destination, List.copyOf(products)));
    }

    private static Item item(ResultSet rs, int quantity) throws SQLException {
        return item(rs, quantity, rs.getBoolean("active") && rs.getLong("stock") >= quantity);
    }

    private static Item item(ResultSet rs, int quantity, boolean available) throws SQLException {
        return new Item(rs.getObject("product_id", UUID.class), rs.getObject("variant_id", UUID.class),
                quantity, rs.getBigDecimal("price"), rs.getBigDecimal("shipping_weight_kg"),
                rs.getBigDecimal("shipping_width_cm"), rs.getBigDecimal("shipping_height_cm"),
                rs.getBigDecimal("shipping_length_cm"), available);
    }
}
