package com.gabrielsena.commerce.catalog.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class PublicCatalogService {
    private final JdbcTemplate jdbc;

    public PublicCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PublicProduct(UUID id, String name, String description, BigDecimal price) { }
    public record PublicVariant(UUID id, String color, String size, BigDecimal price, int quantity, boolean available) { }

    private static final String VISIBLE_PRODUCT = """
            p.store_id = ? AND p.active = true AND EXISTS (
                SELECT 1 FROM catalog_product_variants v
                WHERE v.store_id = p.store_id AND v.product_id = p.product_id AND v.active = true
            )
            """;

    @Transactional(readOnly = true)
    public Page<PublicProduct> products(UUID store, Pageable page) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM catalog_products p WHERE " + VISIBLE_PRODUCT,
                Long.class, store);
        List<PublicProduct> result = jdbc.query("""
                SELECT p.product_id, p.name, p.description, p.price FROM catalog_products p WHERE
                """ + VISIBLE_PRODUCT + " ORDER BY p.name, p.product_id LIMIT ? OFFSET ?",
                (rs, row) -> new PublicProduct(rs.getObject("product_id", UUID.class), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("price")),
                store, page.getPageSize(), page.getOffset());
        return new PageImpl<>(result, page, Objects.requireNonNull(count));
    }

    @Transactional(readOnly = true)
    public PublicProduct product(UUID store, UUID productId) {
        List<PublicProduct> result = jdbc.query("""
                SELECT p.product_id, p.name, p.description, p.price FROM catalog_products p WHERE
                """ + VISIBLE_PRODUCT + " AND p.product_id = ?",
                (rs, row) -> new PublicProduct(rs.getObject("product_id", UUID.class), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("price")), store, productId);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found.");
        }
        return result.getFirst();
    }

    @Transactional(readOnly = true)
    public Page<PublicVariant> variants(UUID store, UUID productId, Pageable page) {
        product(store, productId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_product_variants
                WHERE store_id = ? AND product_id = ? AND active = true
                """, Long.class, store, productId);
        List<PublicVariant> result = jdbc.query("""
                SELECT v.variant_id, v.color, v.size, COALESCE(v.price, p.price) AS effective_price,
                       s.quantity AS quantity
                FROM catalog_product_variants v
                JOIN catalog_products p ON p.store_id = v.store_id AND p.product_id = v.product_id
                JOIN inventory_available_stock s ON s.store_id = v.store_id AND s.product_id = v.product_id
                                                AND s.variant_id = v.variant_id
                WHERE v.store_id = ? AND v.product_id = ? AND v.active = true AND p.active = true
                ORDER BY v.variant_id LIMIT ? OFFSET ?
                """, (rs, row) -> {
                    int quantity = rs.getInt("quantity");
                    return new PublicVariant(rs.getObject("variant_id", UUID.class), rs.getString("color"),
                            rs.getString("size"), rs.getBigDecimal("effective_price"), quantity, quantity > 0);
                }, store, productId, page.getPageSize(), page.getOffset());
        return new PageImpl<>(result, page, Objects.requireNonNull(count));
    }
}
