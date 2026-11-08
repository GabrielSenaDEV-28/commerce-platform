package com.gabrielsena.commerce.inventory.application;

import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.catalog.application.ProductService;
import com.gabrielsena.commerce.catalog.domain.ProductVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final CatalogStoreResolver stores;
    private final ProductService catalog;

    public InventoryService(JdbcTemplate jdbc, CatalogStoreResolver stores, ProductService catalog) {
        this.jdbc = jdbc;
        this.stores = stores;
        this.catalog = catalog;
    }

    public record Balance(UUID productId, UUID variantId, int quantity, long version) { }
    public record Audit(UUID id, Instant openedAt, UUID openedBy, Instant closedAt, UUID closedBy) { }
    public record Movement(UUID id, UUID productId, UUID variantId, UUID auditId, UUID actorId,
                           Instant occurredAt, String type, int beforeQuantity, int afterQuantity, String reason) { }
    public enum MovementType { ENTRY, EXIT }

    @Transactional(readOnly = true)
    public Balance balance(UUID actor, UUID productId, UUID variantId) {
        ProductVariant variant = catalog.getVariant(actor, productId, variantId);
        List<Balance> rows = jdbc.query("""
                SELECT quantity, version FROM inventory_balances
                WHERE store_id = ? AND product_id = ? AND variant_id = ?
                """, (rs, row) -> new Balance(productId, variantId, rs.getInt("quantity"), rs.getLong("version")),
                variant.getStoreId(), productId, variantId);
        return rows.isEmpty() ? new Balance(productId, variantId, 0, 0) : rows.getFirst();
    }

    @Transactional
    public Movement move(UUID actor, UUID productId, UUID variantId, MovementType type, int quantity,
                         String reason, UUID key) {
        if (type == null || quantity <= 0 || key == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Movement requires a positive quantity, type and key.");
        }
        validateReason(reason);
        ProductVariant variant = catalog.getVariant(actor, productId, variantId);
        UUID movementId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO inventory_movement_requests (store_id, idempotency_key, movement_id)
                VALUES (?, ?, ?) ON CONFLICT (store_id, idempotency_key) DO NOTHING
                """, variant.getStoreId(), key, movementId);
        if (inserted == 0) {
            Movement existing = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT m.* FROM inventory_movement_requests r
                    JOIN inventory_movements m ON m.store_id = r.store_id AND m.movement_id = r.movement_id
                    WHERE r.store_id = ? AND r.idempotency_key = ?
                    """, (rs, row) -> movementRow(rs), variant.getStoreId(), key));
            long delta = (long) existing.afterQuantity() - existing.beforeQuantity();
            if (!existing.productId().equals(productId) || !existing.variantId().equals(variantId)
                    || !existing.actorId().equals(actor) || !existing.type().equals(type.name())
                    || delta != (type == MovementType.ENTRY ? quantity : -(long) quantity)
                    || !existing.reason().equals(reason.strip())) {
                throw conflict("Idempotency key already used for a different movement.");
            }
            return existing;
        }
        Balance before = lockBalance(variant);
        long result = type == MovementType.ENTRY ? (long) before.quantity() + quantity : (long) before.quantity() - quantity;
        if (result < 0 || result > Integer.MAX_VALUE) {
            throw conflict("Stock quantity is outside the permitted range.");
        }
        return recordMovement(movementId, variant, null, actor, type.name(), before, (int) result, reason);
    }

    @Transactional
    public Audit openAudit(UUID actor) {
        UUID store = stores.storeFor(actor);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        int inserted = jdbc.update("""
                INSERT INTO inventory_audits (audit_id, store_id, opened_at, opened_by)
                VALUES (?, ?, ?, ?) ON CONFLICT (store_id) WHERE closed_at IS NULL DO NOTHING
                """, id, store, java.sql.Timestamp.from(now), actor);
        if (inserted == 0) {
            throw conflict("An inventory audit is already open.");
        }
        return new Audit(id, now, actor, null, null);
    }

    @Transactional
    public Audit closeAudit(UUID actor, UUID auditId) {
        UUID store = stores.storeFor(actor);
        Audit audit = findAudit(store, auditId, true);
        if (audit.closedAt() != null) {
            throw conflict("Inventory audit is already closed.");
        }
        Instant now = Instant.now();
        jdbc.update("UPDATE inventory_audits SET closed_at = ?, closed_by = ? WHERE store_id = ? AND audit_id = ?",
                java.sql.Timestamp.from(now), actor, store, auditId);
        return new Audit(auditId, audit.openedAt(), audit.openedBy(), now, actor);
    }

    @Transactional(readOnly = true)
    public Audit audit(UUID actor, UUID auditId) {
        return findAudit(stores.storeFor(actor), auditId, false);
    }

    @Transactional(readOnly = true)
    public Page<Audit> audits(UUID actor, Pageable page) {
        UUID store = stores.storeFor(actor);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_audits WHERE store_id = ?", Long.class, store);
        List<Audit> results = jdbc.query("""
                SELECT * FROM inventory_audits WHERE store_id = ?
                ORDER BY opened_at DESC, audit_id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> auditRow(rs), store, page.getPageSize(), page.getOffset());
        return new PageImpl<>(results, page, Objects.requireNonNull(count));
    }

    @Transactional
    public Movement count(UUID actor, UUID auditId, UUID productId, UUID variantId,
                          long expectedVersion, int quantity, String reason) {
        if (expectedVersion < 0 || quantity < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Count and version must be non-negative.");
        }
        validateReason(reason);
        UUID store = stores.storeFor(actor);
        Audit audit = findAudit(store, auditId, true);
        if (audit.closedAt() != null) {
            throw conflict("Inventory audit is already closed.");
        }
        ProductVariant variant = catalog.getVariant(actor, productId, variantId);
        Balance before = lockBalance(variant);
        if (before.version() != expectedVersion) {
            throw conflict("Stock changed since it was observed. Review the count.");
        }
        return recordMovement(UUID.randomUUID(), variant, auditId, actor, "COUNT", before, quantity, reason);
    }

    @Transactional(readOnly = true)
    public Page<Movement> variantHistory(UUID actor, UUID productId, UUID variantId, Pageable page) {
        ProductVariant variant = catalog.getVariant(actor, productId, variantId);
        UUID store = variant.getStoreId();
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_movements WHERE store_id = ? AND product_id = ? AND variant_id = ?
                """, Long.class, store, productId, variantId);
        List<Movement> results = jdbc.query("""
                SELECT * FROM inventory_movements WHERE store_id = ? AND product_id = ? AND variant_id = ?
                ORDER BY occurred_at DESC, movement_id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> movementRow(rs), store, productId, variantId, page.getPageSize(), page.getOffset());
        return new PageImpl<>(results, page, Objects.requireNonNull(count));
    }

    @Transactional(readOnly = true)
    public Page<Movement> auditHistory(UUID actor, UUID auditId, Pageable page) {
        UUID store = stores.storeFor(actor);
        findAudit(store, auditId, false);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_movements WHERE store_id = ? AND audit_id = ?
                """, Long.class, store, auditId);
        List<Movement> results = jdbc.query("""
                SELECT * FROM inventory_movements WHERE store_id = ? AND audit_id = ?
                ORDER BY occurred_at DESC, movement_id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> movementRow(rs), store, auditId, page.getPageSize(), page.getOffset());
        return new PageImpl<>(results, page, Objects.requireNonNull(count));
    }

    private Balance lockBalance(ProductVariant variant) {
        jdbc.update("""
                INSERT INTO inventory_balances (store_id, product_id, variant_id, quantity, version)
                VALUES (?, ?, ?, 0, 0) ON CONFLICT (variant_id) DO NOTHING
                """, variant.getStoreId(), variant.getProductId(), variant.getId());
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT quantity, version FROM inventory_balances
                WHERE store_id = ? AND product_id = ? AND variant_id = ? FOR UPDATE
                """, (rs, row) -> new Balance(variant.getProductId(), variant.getId(),
                rs.getInt("quantity"), rs.getLong("version")),
                variant.getStoreId(), variant.getProductId(), variant.getId()));
    }

    private Movement recordMovement(UUID id, ProductVariant variant, UUID auditId, UUID actor, String type,
                                    Balance before, int quantity, String reason) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                UPDATE inventory_balances SET quantity = ?, version = version + 1
                WHERE store_id = ? AND product_id = ? AND variant_id = ?
                """, quantity, variant.getStoreId(), variant.getProductId(), variant.getId());
        jdbc.update("""
                INSERT INTO inventory_movements (movement_id, store_id, product_id, variant_id, audit_id,
                    actor_id, occurred_at, movement_type, before_quantity, after_quantity, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, variant.getStoreId(), variant.getProductId(), variant.getId(), auditId, actor,
                java.sql.Timestamp.from(now), type, before.quantity(), quantity, reason.strip());
        return new Movement(id, variant.getProductId(), variant.getId(), auditId, actor, now, type,
                before.quantity(), quantity, reason.strip());
    }

    private Audit findAudit(UUID store, UUID id, boolean lock) {
        String sql = "SELECT * FROM inventory_audits WHERE store_id = ? AND audit_id = ?" + (lock ? " FOR UPDATE" : "");
        List<Audit> results = jdbc.query(sql, (rs, row) -> auditRow(rs), store, id);
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory audit not found.");
        }
        return results.getFirst();
    }

    private Audit auditRow(ResultSet rs) throws SQLException {
        var closed = rs.getTimestamp("closed_at");
        return new Audit(rs.getObject("audit_id", UUID.class), rs.getTimestamp("opened_at").toInstant(),
                rs.getObject("opened_by", UUID.class), closed == null ? null : closed.toInstant(),
                rs.getObject("closed_by", UUID.class));
    }

    private Movement movementRow(ResultSet rs) throws SQLException {
        return new Movement(rs.getObject("movement_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getObject("audit_id", UUID.class),
                rs.getObject("actor_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("movement_type"), rs.getInt("before_quantity"), rs.getInt("after_quantity"),
                rs.getString("reason"));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason of at most 500 characters is required.");
        }
    }
}
