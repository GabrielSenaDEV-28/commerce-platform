package com.gabrielsena.commerce.cart.application;

import com.gabrielsena.commerce.security.OpaqueTokens;
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
public class ShoppingCartService {
    private static final int MAX_ITEMS = 100;
    private final JdbcTemplate jdbc;

    public ShoppingCartService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CreatedCart(String token, Instant expiresAt) { }
    public record Cart(Instant expiresAt, List<CartItem> items) { }
    public record CartItem(UUID productId, UUID variantId, String name, String color, String size,
                           BigDecimal price, int quantity, int stockQuantity, boolean available) { }
    private record CartIdentity(UUID id, Instant expiresAt) { }
    private record VariantState(boolean active, int stockQuantity) { }

    @Transactional
    public CreatedCart create(UUID store) {
        String token = OpaqueTokens.generate();
        Instant expires = jdbc.queryForObject("""
                INSERT INTO shopping_carts (cart_id, store_id, token_hash, expires_at)
                VALUES (?, ?, ?, clock_timestamp() + INTERVAL '30 days') RETURNING expires_at
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), UUID.randomUUID(), store, OpaqueTokens.hash(token));
        return new CreatedCart(token, expires);
    }

    @Transactional(readOnly = true)
    public Cart get(UUID store, String token) {
        return read(store, find(store, token, false));
    }

    @Transactional
    public Cart setQuantity(UUID store, String token, UUID product, UUID variant, int quantity) {
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive.");
        }
        CartIdentity cart = find(store, token, true);
        requireEditable(store, cart.id());
        List<VariantState> variants = jdbc.query("""
                SELECT p.active AND v.active AS active, s.quantity AS stock_quantity
                FROM catalog_product_variants v
                JOIN catalog_products p ON p.store_id = v.store_id AND p.product_id = v.product_id
                JOIN inventory_available_stock s ON s.store_id = v.store_id AND s.product_id = v.product_id
                    AND s.variant_id = v.variant_id
                WHERE v.store_id = ? AND v.product_id = ? AND v.variant_id = ?
                """, (rs, row) -> new VariantState(rs.getBoolean("active"), rs.getInt("stock_quantity")),
                store, product, variant);
        if (variants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found.");
        }
        VariantState state = variants.getFirst();
        if (!state.active() || state.stockQuantity() < quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variant is unavailable in the requested quantity.");
        }
        Integer existing = jdbc.query("""
                SELECT quantity FROM shopping_cart_items WHERE store_id = ? AND cart_id = ? AND variant_id = ?
                """, (rs, row) -> rs.getInt("quantity"), store, cart.id(), variant).stream().findFirst().orElse(null);
        if (existing == null) {
            int count = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shopping_cart_items WHERE store_id = ? AND cart_id = ?",
                    Integer.class, store, cart.id()));
            if (count >= MAX_ITEMS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart item limit reached.");
            }
            jdbc.update("""
                    INSERT INTO shopping_cart_items (store_id, cart_id, product_id, variant_id, quantity)
                    VALUES (?, ?, ?, ?, ?)
                    """, store, cart.id(), product, variant, quantity);
            cart = renew(store, cart.id());
        } else if (existing != quantity) {
            jdbc.update("""
                    UPDATE shopping_cart_items SET quantity = ?
                    WHERE store_id = ? AND cart_id = ? AND product_id = ? AND variant_id = ?
                    """, quantity, store, cart.id(), product, variant);
            cart = renew(store, cart.id());
        }
        return read(store, cart);
    }

    @Transactional
    public Cart remove(UUID store, String token, UUID product, UUID variant) {
        CartIdentity cart = find(store, token, true);
        requireEditable(store, cart.id());
        int deleted = jdbc.update("""
                DELETE FROM shopping_cart_items WHERE store_id = ? AND cart_id = ? AND product_id = ? AND variant_id = ?
                """, store, cart.id(), product, variant);
        if (deleted > 0) {
            cart = renew(store, cart.id());
        }
        return read(store, cart);
    }

    public UUID cartId(UUID store, String token, boolean lock) {
        return find(store, token, lock).id();
    }

    private void requireEditable(UUID store, UUID cart) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM checkout_reservations
                  WHERE store_id = ? AND cart_id = ? AND cancelled_at IS NULL
                    AND expires_at > statement_timestamp())
                """, Boolean.class, store, cart);
        if (Boolean.TRUE.equals(active)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancel checkout before changing the cart.");
        }
    }

    private CartIdentity find(UUID store, String token, boolean lock) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw notFound();
        }
        List<CartIdentity> carts = jdbc.query("""
                SELECT cart_id, expires_at FROM shopping_carts
                WHERE store_id = ? AND token_hash = ? AND expires_at > clock_timestamp()
                """ + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new CartIdentity(rs.getObject("cart_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()), store, OpaqueTokens.hash(token));
        if (carts.isEmpty()) {
            throw notFound();
        }
        if (lock && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT expires_at > clock_timestamp() FROM shopping_carts WHERE store_id = ? AND cart_id = ?
                """, Boolean.class, store, carts.getFirst().id()))) {
            throw notFound();
        }
        return carts.getFirst();
    }

    private CartIdentity renew(UUID store, UUID cart) {
        return jdbc.queryForObject("""
                UPDATE shopping_carts SET expires_at = clock_timestamp() + INTERVAL '30 days'
                WHERE store_id = ? AND cart_id = ? RETURNING cart_id, expires_at
                """, (rs, row) -> new CartIdentity(rs.getObject("cart_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant()), store, cart);
    }

    private Cart read(UUID store, CartIdentity cart) {
        List<CartItem> items = jdbc.query("""
                SELECT i.product_id, i.variant_id, p.name, v.color, v.size, COALESCE(v.price, p.price) AS price,
                       i.quantity, s.quantity AS stock_quantity,
                       p.active AND v.active AND s.quantity >= i.quantity AS available
                FROM shopping_cart_items i
                JOIN catalog_products p ON p.store_id = i.store_id AND p.product_id = i.product_id
                JOIN catalog_product_variants v ON v.store_id = i.store_id AND v.product_id = i.product_id
                    AND v.variant_id = i.variant_id
                JOIN inventory_available_stock s ON s.store_id = i.store_id AND s.product_id = i.product_id
                    AND s.variant_id = i.variant_id
                WHERE i.store_id = ? AND i.cart_id = ? ORDER BY i.product_id, i.variant_id
                """, (rs, row) -> new CartItem(rs.getObject("product_id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getString("name"), rs.getString("color"),
                rs.getString("size"), rs.getBigDecimal("price"), rs.getInt("quantity"),
                rs.getInt("stock_quantity"), rs.getBoolean("available")), store, cart.id());
        return new Cart(cart.expiresAt(), items);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Cart not found.");
    }
}
