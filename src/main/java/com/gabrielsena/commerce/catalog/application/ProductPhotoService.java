package com.gabrielsena.commerce.catalog.application;

import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProductPhotoService {
    private static final Logger log = LoggerFactory.getLogger(ProductPhotoService.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final CatalogStoreResolver stores;
    private final ProductPhotoStorage storage;
    private final TransactionTemplate transactions;

    public record Photo(UUID id, String url, int position) { }
    private record Entry(UUID id, String key, int position, String status) { }

    public ProductPhotoService(JdbcTemplate jdbc, CatalogStoreResolver stores, ProductPhotoStorage storage,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.stores = stores;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Photo upload(UUID actor, UUID product, MultipartFile file) throws IOException {
        UUID store = stores.storeFor(actor);
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photo must be at most 10 MiB.");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photo must be at most 10 MiB.");
        }
        String contentType = validateImage(bytes);
        UUID id = UUID.randomUUID();
        String key = "products/" + store + "/" + product + "/" + id;
        transactions.executeWithoutResult(status -> {
            lockProduct(store, product);
            int count = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM catalog_product_photos
                    WHERE store_id = ? AND product_id = ? AND status IN ('UPLOADING', 'READY')
                    """, Integer.class, store, product));
            if (count >= 10) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Product photo limit reached.");
            }
            int position = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT COALESCE(MAX(position), -1) + 1 FROM catalog_product_photos
                    WHERE store_id = ? AND product_id = ?
                    """, Integer.class, store, product));
            jdbc.update("""
                    INSERT INTO catalog_product_photos (photo_id, store_id, product_id, object_key, content_type,
                                                        position, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'UPLOADING', ?)
                    """, id, store, product, key, contentType, position, actor);
        });
        try {
            storage.put(key, contentType, bytes);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> jdbc.update("""
                    UPDATE catalog_product_photos SET status = 'DELETING'
                    WHERE store_id = ? AND product_id = ? AND photo_id = ? AND status = 'UPLOADING'
                    """, store, product, id));
            throw exception;
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            lockProduct(store, product);
            int position = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT COALESCE(MAX(position), -1) + 1 FROM catalog_product_photos
                    WHERE store_id = ? AND product_id = ? AND status = 'READY'
                    """, Integer.class, store, product));
            int changed = jdbc.update("""
                    UPDATE catalog_product_photos SET status = 'READY', position = ?
                    WHERE store_id = ? AND product_id = ? AND photo_id = ? AND status = 'UPLOADING'
                    """, position, store, product, id);
            if (changed != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Photo upload is no longer active.");
            }
            history(store, product, actor, null, id.toString());
            return entries(store, product, "READY").stream().filter(entry -> entry.id().equals(id))
                    .map(this::photo).findFirst().orElseThrow();
        }));
    }

    public List<Photo> list(UUID actor, UUID product) {
        UUID store = stores.storeFor(actor);
        requireProduct(store, product);
        return entries(store, product, "READY").stream().map(this::photo).toList();
    }

    public void delete(UUID actor, UUID product, UUID photo) {
        UUID store = stores.storeFor(actor);
        String key = Objects.requireNonNull(transactions.execute(status -> {
            lockProduct(store, product);
            List<Entry> photos = jdbc.query("""
                    SELECT photo_id, object_key, position, status FROM catalog_product_photos
                    WHERE store_id = ? AND product_id = ? AND photo_id = ?
                    """, (rs, row) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getInt(3), rs.getString(4)), store, product, photo);
            if (photos.isEmpty() || photos.getFirst().status().equals("UPLOADING")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found.");
            }
            if (photos.getFirst().status().equals("READY")) {
                jdbc.update("UPDATE catalog_product_photos SET status = 'DELETING' WHERE photo_id = ?", photo);
                history(store, product, actor, photo.toString(), null);
            }
            return photos.getFirst().key();
        }));
        storage.delete(key);
        transactions.executeWithoutResult(status -> jdbc.update("""
                DELETE FROM catalog_product_photos WHERE store_id = ? AND product_id = ? AND photo_id = ? AND status = 'DELETING'
                """, store, product, photo));
    }

    public List<Photo> reorder(UUID actor, UUID product, List<UUID> ids) {
        UUID store = stores.storeFor(actor);
        return Objects.requireNonNull(transactions.execute(status -> {
            lockProduct(store, product);
            List<Entry> photos = entries(store, product, "READY");
            if (ids == null || ids.size() != photos.size() || new HashSet<>(ids).size() != ids.size()
                    || !new HashSet<>(ids).equals(new HashSet<>(photos.stream().map(Entry::id).toList()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide every photo exactly once.");
            }
            List<UUID> old = photos.stream().map(Entry::id).toList();
            if (old.equals(ids)) {
                return photos.stream().map(this::photo).toList();
            }
            for (int i = 0; i < ids.size(); i++) {
                jdbc.update("UPDATE catalog_product_photos SET position = ? WHERE store_id = ? AND product_id = ? AND photo_id = ?",
                        i, store, product, ids.get(i));
            }
            history(store, product, actor, old.toString(), ids.toString());
            return entries(store, product, "READY").stream().map(this::photo).toList();
        }));
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        jdbc.update("""
                UPDATE catalog_product_photos SET status = 'DELETING'
                WHERE status = 'UPLOADING' AND created_at < clock_timestamp() - INTERVAL '30 minutes'
                """);
        List<Entry> pending = jdbc.query("""
                SELECT photo_id, object_key, position, status FROM catalog_product_photos
                WHERE status = 'DELETING' ORDER BY cleanup_attempted_at NULLS FIRST, created_at LIMIT 100
                """, (rs, row) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getInt(3), rs.getString(4)));
        for (Entry entry : pending) {
            try {
                jdbc.update("""
                        UPDATE catalog_product_photos SET cleanup_attempted_at = clock_timestamp()
                        WHERE photo_id = ? AND status = 'DELETING'
                        """, entry.id());
                storage.delete(entry.key());
                jdbc.update("DELETE FROM catalog_product_photos WHERE photo_id = ? AND status = 'DELETING'", entry.id());
            } catch (ResponseStatusException exception) {
                log.warn("Photo cleanup failed for {}", entry.id(), exception);
            }
        }
    }

    private Photo photo(Entry entry) {
        return new Photo(entry.id(), storage.url(entry.key()), entry.position());
    }

    private void history(UUID store, UUID product, UUID actor, String oldValue, String newValue) {
        jdbc.update("""
                INSERT INTO catalog_product_history
                  (history_id, store_id, product_id, actor_id, occurred_at, action, field_name, old_value, new_value)
                VALUES (?, ?, ?, ?, clock_timestamp(), 'UPDATED', 'photos', ?, ?)
                """, UUID.randomUUID(), store, product, actor, oldValue, newValue);
    }

    private List<Entry> entries(UUID store, UUID product, String status) {
        return jdbc.query("""
                SELECT photo_id, object_key, position, status FROM catalog_product_photos
                WHERE store_id = ? AND product_id = ? AND status = ? ORDER BY position, photo_id
                """, (rs, row) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getInt(3), rs.getString(4)), store, product, status);
    }

    private void requireProduct(UUID store, UUID product) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM catalog_products WHERE store_id = ? AND product_id = ?)
                """, Boolean.class, store, product))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found.");
        }
    }

    private void lockProduct(UUID store, UUID product) {
        if (jdbc.query("SELECT product_id FROM catalog_products WHERE store_id = ? AND product_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), store, product).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found.");
        }
    }

    private String validateImage(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String type = switch (format) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image.");
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 40_000_000L || reader.read(0) == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image dimensions or content.");
                }
                return type;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image content.", exception);
        }
    }
}
