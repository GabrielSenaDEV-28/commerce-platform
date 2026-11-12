package com.gabrielsena.commerce.cart.application;

import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.security.OpaqueTokens;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    private final CustomerAuthService customers;

    public ShoppingCartService(JdbcTemplate jdbc, CustomerAuthService customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    public record CreatedCart(String token, Instant expiresAt) { }
    public record CustomerCart(String token, Instant expiresAt, Cart cart) { }
    public record Cart(Instant expiresAt, List<CartItem> items) { }
    public record CartItem(UUID productId, UUID variantId, String name, String color, String size,
                           BigDecimal price, int quantity, int stockQuantity, boolean available) { }
    private record CartIdentity(UUID id, Instant expiresAt, UUID customerId) { }
    private record MergeSource(UUID id, UUID customerId, UUID targetId, boolean expired) { }
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

    @Transactional
    public CustomerCart recover(UUID store, Jwt jwt, String sourceToken) {
        UUID customer = customers.customer(store, jwt).id();
        jdbc.update("""
                INSERT INTO shopping_carts (cart_id, store_id, customer_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, clock_timestamp() + INTERVAL '30 days')
                ON CONFLICT (store_id, customer_id) DO NOTHING
                """, UUID.randomUUID(), store, customer, OpaqueTokens.hash(OpaqueTokens.generate()));
        UUID target = jdbc.queryForObject("""
                SELECT cart_id FROM shopping_carts WHERE store_id = ? AND customer_id = ? FOR UPDATE
                """, UUID.class, store, customer);
        if (sourceToken != null) {
            merge(store, target, sourceToken);
        }
        // Expiration limits access, not retention: authenticated recovery retains all saved items.
        CartIdentity cart = renew(store, target);
        String token = OpaqueTokens.generate();
        jdbc.update("""
                INSERT INTO customer_cart_access_tokens (token_hash, store_id, cart_id, expires_at)
                VALUES (?, ?, ?, ?)
                """, OpaqueTokens.hash(token), store, target, java.sql.Timestamp.from(cart.expiresAt()));
        return new CustomerCart(token, cart.expiresAt(), read(store, cart));
    }

    private void merge(UUID store, UUID target, String sourceToken) {
        requireToken(sourceToken);
        List<MergeSource> sources = jdbc.query("""
                SELECT c.cart_id, c.customer_id, c.merged_into_cart_id,
                       c.expires_at <= clock_timestamp() AS expired
                FROM shopping_carts c
                WHERE c.store_id = ? AND (c.customer_id IS NULL OR c.cart_id = ?)
                    AND (c.token_hash = ? OR EXISTS (
                    SELECT 1 FROM customer_cart_access_tokens a WHERE a.store_id = c.store_id
                        AND a.cart_id = c.cart_id AND a.token_hash = ? AND a.expires_at > clock_timestamp()))
                FOR UPDATE OF c
                """, (rs, row) -> new MergeSource(rs.getObject("cart_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("merged_into_cart_id", UUID.class),
                rs.getBoolean("expired")), store, target, OpaqueTokens.hash(sourceToken), OpaqueTokens.hash(sourceToken));
        if (sources.isEmpty()) throw notFound();
        MergeSource source = sources.getFirst();
        if (source.customerId() != null || source.targetId() != null) {
            if (target.equals(source.id()) || target.equals(source.targetId())) return;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cart belongs to another customer.");
        }
        if (source.expired() || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT expires_at > clock_timestamp() FROM shopping_carts WHERE store_id = ? AND cart_id = ?
                """, Boolean.class, store, source.id()))) throw notFound();
        requireEditable(store, target);
        requireEditable(store, source.id());
        Boolean exceedsLimits = jdbc.queryForObject("""
                SELECT COUNT(*) > ? OR COALESCE(MAX(quantity), 0) > 2147483647 FROM (
                    SELECT variant_id, SUM(quantity::bigint) AS quantity FROM shopping_cart_items
                    WHERE store_id = ? AND cart_id IN (?, ?) GROUP BY variant_id
                ) combined
                """, Boolean.class, MAX_ITEMS, store, target, source.id());
        if (Boolean.TRUE.equals(exceedsLimits)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Merged cart exceeds item or quantity limits.");
        }
        jdbc.update("""
                INSERT INTO shopping_cart_items (store_id, cart_id, product_id, variant_id, quantity)
                SELECT store_id, ?, product_id, variant_id, quantity FROM shopping_cart_items
                WHERE store_id = ? AND cart_id = ?
                ON CONFLICT (cart_id, variant_id) DO UPDATE
                    SET quantity = shopping_cart_items.quantity + EXCLUDED.quantity
                """, target, store, source.id());
        jdbc.update("DELETE FROM shopping_cart_items WHERE store_id = ? AND cart_id = ?", store, source.id());
        jdbc.update("""
                UPDATE shopping_carts SET merged_into_cart_id = ? WHERE store_id = ? AND cart_id = ?
                """, target, store, source.id());
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
        requireToken(token);
        List<CartIdentity> carts = jdbc.query("""
                SELECT c.cart_id, c.expires_at, c.customer_id FROM shopping_carts c
                WHERE c.store_id = ? AND c.merged_into_cart_id IS NULL AND c.expires_at > clock_timestamp()
                    AND ((c.customer_id IS NULL AND c.token_hash = ?) OR
                        (c.customer_id IS NOT NULL AND EXISTS (
                            SELECT 1 FROM customer_cart_access_tokens a WHERE a.store_id = c.store_id
                                AND a.cart_id = c.cart_id AND a.token_hash = ? AND a.expires_at > clock_timestamp())))
                """ + (lock ? " FOR UPDATE OF c" : ""),
                (rs, row) -> new CartIdentity(rs.getObject("cart_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getObject("customer_id", UUID.class)),
                store, OpaqueTokens.hash(token), OpaqueTokens.hash(token));
        if (carts.isEmpty()) {
            throw notFound();
        }
        if (lock && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT c.expires_at > clock_timestamp() AND (c.customer_id IS NULL OR EXISTS (
                    SELECT 1 FROM customer_cart_access_tokens a WHERE a.store_id = c.store_id
                        AND a.cart_id = c.cart_id AND a.token_hash = ? AND a.expires_at > clock_timestamp()))
                FROM shopping_carts c WHERE c.store_id = ? AND c.cart_id = ?
                """, Boolean.class, OpaqueTokens.hash(token), store, carts.getFirst().id()))) {
            throw notFound();
        }
        CartIdentity cart = carts.getFirst();
        if (cart.customerId() != null) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken authenticated) || !authenticated.isAuthenticated()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required.");
            }
            if (!cart.customerId().equals(customers.customer(store, authenticated.getToken()).id())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cart belongs to another customer.");
            }
        }
        return cart;
    }

    private static void requireToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw notFound();
    }

    private CartIdentity renew(UUID store, UUID cart) {
        return jdbc.queryForObject("""
                UPDATE shopping_carts SET expires_at = clock_timestamp() + INTERVAL '30 days'
                WHERE store_id = ? AND cart_id = ? RETURNING cart_id, expires_at, customer_id
                """, (rs, row) -> new CartIdentity(rs.getObject("cart_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(), rs.getObject("customer_id", UUID.class)), store, cart);
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
