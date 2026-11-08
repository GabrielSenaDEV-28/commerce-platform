package com.gabrielsena.commerce.catalog.application;

import com.gabrielsena.commerce.catalog.CatalogConflictException;
import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.catalog.ItemTypeNotFoundException;
import com.gabrielsena.commerce.catalog.ItemTypeRepository;
import com.gabrielsena.commerce.catalog.ProductHistoryRepository;
import com.gabrielsena.commerce.catalog.ProductRepository;
import com.gabrielsena.commerce.catalog.ProductVariantRepository;
import com.gabrielsena.commerce.catalog.api.ProductRequest;
import com.gabrielsena.commerce.catalog.api.ProductUpdateRequest;
import com.gabrielsena.commerce.catalog.api.VariantRequest;
import com.gabrielsena.commerce.catalog.domain.ItemType;
import com.gabrielsena.commerce.catalog.domain.Product;
import com.gabrielsena.commerce.catalog.domain.ProductHistory;
import com.gabrielsena.commerce.catalog.domain.ProductVariant;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProductService {
    private final CatalogStoreResolver stores;
    private final ItemTypeRepository types;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductHistoryRepository history;

    public ProductService(CatalogStoreResolver stores, ItemTypeRepository types, ProductRepository products,
                          ProductVariantRepository variants, ProductHistoryRepository history) {
        this.stores = stores;
        this.types = types;
        this.products = products;
        this.variants = variants;
        this.history = history;
    }

    @Transactional
    public Product create(UUID actor, ProductRequest request) {
        UUID store = stores.storeFor(actor);
        ItemType type = types.lockByIdAndStoreId(request.itemTypeId(), store)
                .filter(ItemType::isActive).orElseThrow(ItemTypeNotFoundException::new);
        Instant now = Instant.now();
        Product product = Product.create(store, type.getId(), request.sku(), request.name(),
                request.description(), request.price(), actor, now);
        saveProduct(product);
        change(product, null, actor, now, "CREATED", "sku", null, product.getSku());
        change(product, null, actor, now, "CREATED", "name", null, product.getName());
        change(product, null, actor, now, "CREATED", "description", null, product.getDescription());
        change(product, null, actor, now, "CREATED", "price", null, product.getPrice());
        change(product, null, actor, now, "CREATED", "itemTypeId", null, product.getItemTypeId());
        for (VariantRequest variant : request.variants()) {
            createVariant(product, type, variant, actor, now);
        }
        return product;
    }

    @Transactional(readOnly = true)
    public Product get(UUID actor, UUID id) {
        return product(id, stores.storeFor(actor));
    }

    @Transactional(readOnly = true)
    public Page<Product> list(UUID actor, boolean active, Pageable page) {
        return products.findByStoreIdAndActive(stores.storeFor(actor), active, page);
    }

    @Transactional
    public Product update(UUID actor, UUID id, ProductUpdateRequest request) {
        Product p = lockProduct(id, stores.storeFor(actor));
        requireActive(p);
        if (p.getSku().equals(request.sku().strip())
                && p.getName().equals(request.name().strip())
                && p.getDescription().equals(request.description().strip())
                && p.getPrice().compareTo(request.price()) == 0) {
            return p;
        }
        Instant now = Instant.now();
        String oldSku = p.getSku();
        String oldName = p.getName();
        String oldDescription = p.getDescription();
        BigDecimal oldPrice = p.getPrice();
        p.update(request.sku(), request.name(), request.description(), request.price(), actor, now);
        saveProduct(p);
        change(p, null, actor, now, "UPDATED", "sku", oldSku, p.getSku());
        change(p, null, actor, now, "UPDATED", "name", oldName, p.getName());
        change(p, null, actor, now, "UPDATED", "description", oldDescription, p.getDescription());
        change(p, null, actor, now, "UPDATED", "price", oldPrice, p.getPrice());
        return p;
    }

    @Transactional
    public Product setActive(UUID actor, UUID id, boolean active) {
        Product p = lockProduct(id, stores.storeFor(actor));
        if (p.isActive() != active) {
            Instant now = Instant.now();
            p.setActive(active, actor, now);
            change(p, null, actor, now, active ? "REACTIVATED" : "DEACTIVATED", "active", !active, active);
        }
        return p;
    }

    @Transactional(readOnly = true)
    public Page<ProductVariant> variants(UUID actor, UUID productId, boolean active, Pageable page) {
        UUID store = stores.storeFor(actor);
        product(productId, store);
        return variants.findByStoreIdAndProductIdAndActive(store, productId, active, page);
    }

    @Transactional
    public ProductVariant addVariant(UUID actor, UUID productId, VariantRequest request) {
        Product p = lockProduct(productId, stores.storeFor(actor));
        requireActive(p);
        ItemType type = types.findByIdAndStoreIdAndActiveTrue(p.getItemTypeId(), p.getStoreId())
                .orElseThrow(ItemTypeNotFoundException::new);
        return createVariant(p, type, request, actor, Instant.now());
    }

    @Transactional
    public ProductVariant updateVariantPrice(UUID actor, UUID productId, UUID id, BigDecimal price) {
        Product p = lockProduct(productId, stores.storeFor(actor));
        requireActive(p);
        ProductVariant variant = variant(p, id);
        if (!variant.isActive()) {
            throw notFound();
        }
        if (samePrice(variant.getPrice(), price)) {
            return variant;
        }
        Instant now = Instant.now();
        BigDecimal old = variant.getPrice();
        variant.setPrice(price, actor, now);
        change(p, id, actor, now, "UPDATED", "price", old, price);
        return variant;
    }

    @Transactional
    public ProductVariant setVariantActive(UUID actor, UUID productId, UUID id, boolean active) {
        Product p = lockProduct(productId, stores.storeFor(actor));
        requireActive(p);
        ProductVariant variant = variant(p, id);
        if (variant.isActive() != active) {
            Instant now = Instant.now();
            variant.setActive(active, actor, now);
            change(p, id, actor, now, active ? "REACTIVATED" : "DEACTIVATED", "active", !active, active);
        }
        return variant;
    }

    @Transactional(readOnly = true)
    public Page<ProductHistory> history(UUID actor, UUID id, Pageable page) {
        UUID store = stores.storeFor(actor);
        product(id, store);
        return history.findByStoreIdAndProductId(store, id, page);
    }

    private ProductVariant createVariant(Product p, ItemType type, VariantRequest request, UUID actor, Instant now) {
        validateVariant(type, request);
        ProductVariant variant = ProductVariant.create(p, request.color(), request.size(), request.price(), actor, now);
        try {
            variants.saveAndFlush(variant);
        } catch (DataIntegrityViolationException exception) {
            translateUnique(exception);
        }
        change(p, variant.getId(), actor, now, "CREATED", "color", null, variant.getColor());
        change(p, variant.getId(), actor, now, "CREATED", "size", null, variant.getSize());
        change(p, variant.getId(), actor, now, "CREATED", "price", null, variant.getPrice());
        change(p, variant.getId(), actor, now, "CREATED", "active", null, true);
        return variant;
    }

    private void validateVariant(ItemType type, VariantRequest variant) {
        String color = variant.color() == null ? null : variant.color().strip();
        String size = variant.size() == null ? null : variant.size().strip();
        if (type.isHasColor() != (color != null && !color.isBlank())
                || type.isHasSize() != (size != null && !size.isBlank())
                || (color != null && color.isBlank()) || (size != null && size.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variant attributes do not match item type.");
        }
    }

    private Product product(UUID id, UUID store) {
        return products.findByIdAndStoreId(id, store).orElseThrow(ProductService::notFound);
    }

    private Product lockProduct(UUID id, UUID store) {
        return products.lockByIdAndStoreId(id, store).orElseThrow(ProductService::notFound);
    }

    private ProductVariant variant(Product product, UUID id) {
        return variants.findByIdAndStoreIdAndProductId(id, product.getStoreId(), product.getId())
                .orElseThrow(ProductService::notFound);
    }

    private void requireActive(Product product) {
        if (!product.isActive()) {
            throw new CatalogConflictException("Product is inactive.");
        }
    }

    private void saveProduct(Product product) {
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            translateUnique(exception);
        }
    }

    private void translateUnique(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                if ("uk_product_store_sku".equals(violation.getConstraintName())) {
                    throw new CatalogConflictException("SKU is already in use.");
                }
                if ("uk_variant_product_combination".equals(violation.getConstraintName())) {
                    throw new CatalogConflictException("Variant combination already exists.");
                }
            }
        }
        throw exception;
    }

    private void change(Product p, UUID variantId, UUID actor, Instant now, String action,
                        String field, Object before, Object after) {
        boolean equal = before instanceof BigDecimal oldPrice && after instanceof BigDecimal newPrice
                ? oldPrice.compareTo(newPrice) == 0 : Objects.equals(before, after);
        if (!"UPDATED".equals(action) || !equal) {
            history.save(ProductHistory.record(p, variantId, actor, now, action, field, before, after));
        }
    }

    private boolean samePrice(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product or variant not found.");
    }
}
