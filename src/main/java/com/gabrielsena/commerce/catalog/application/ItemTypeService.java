package com.gabrielsena.commerce.catalog.application;

import com.gabrielsena.commerce.catalog.CatalogConflictException;
import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.catalog.ProductRepository;
import com.gabrielsena.commerce.catalog.ItemTypeHistoryRepository;
import com.gabrielsena.commerce.catalog.ItemTypeNameInUseException;
import com.gabrielsena.commerce.catalog.ItemTypeNotFoundException;
import com.gabrielsena.commerce.catalog.ItemTypeRepository;
import com.gabrielsena.commerce.catalog.domain.ItemType;
import com.gabrielsena.commerce.catalog.domain.ItemTypeHistory;
import com.gabrielsena.commerce.catalog.domain.ItemTypeValues;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ItemTypeService {
    private final ItemTypeRepository items;
    private final ItemTypeHistoryRepository history;
    private final CatalogStoreResolver stores;
    private final ProductRepository products;

    public ItemTypeService(ItemTypeRepository items, ItemTypeHistoryRepository history,
                           CatalogStoreResolver stores, ProductRepository products) {
        this.items = items;
        this.history = history;
        this.stores = stores;
        this.products = products;
    }

    @Transactional
    public ItemType create(UUID actorId, ItemTypeValues values) {
        UUID storeId = stores.storeFor(actorId);
        Instant now = Instant.now();
        ItemType item = ItemType.create(storeId, actorId, values, now);
        save(item);
        history.save(ItemTypeHistory.record(item, actorId, now, ItemTypeHistory.Action.CREATED, null, item.values()));
        return item;
    }

    @Transactional(readOnly = true)
    public ItemType get(UUID actorId, UUID id) {
        return items.findByIdAndStoreIdAndActiveTrue(id, stores.storeFor(actorId)).orElseThrow(ItemTypeNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<ItemType> list(UUID actorId, boolean active, Pageable pageable) {
        return items.findByStoreIdAndActive(stores.storeFor(actorId), active, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ItemTypeHistory> history(UUID actorId, UUID id, Pageable pageable) {
        UUID storeId = stores.storeFor(actorId);
        if (!history.existsByItemTypeIdAndStoreId(id, storeId)) {
            throw new ItemTypeNotFoundException();
        }
        return history.findByItemTypeIdAndStoreId(id, storeId, pageable);
    }

    @Transactional
    public ItemType update(UUID actorId, UUID id, ItemTypeValues values) {
        ItemType item = lockItem(id, stores.storeFor(actorId));
        if (!item.isActive()) {
            throw new ItemTypeNotFoundException();
        }
        ItemTypeValues oldValues = item.values();
        if (oldValues.equals(values)) {
            return item;
        }
        if (products.existsByStoreIdAndItemTypeId(item.getStoreId(), id)
                && (oldValues.hasColor() != values.hasColor() || oldValues.hasSize() != values.hasSize()
                || oldValues.sizeFormat() != values.sizeFormat())) {
            throw new CatalogConflictException("Item type configuration is used by products.");
        }
        Instant now = Instant.now();
        item.update(values, actorId, now);
        save(item);
        history.save(ItemTypeHistory.record(item, actorId, now, ItemTypeHistory.Action.UPDATED, oldValues, item.values()));
        return item;
    }

    @Transactional
    public void delete(UUID actorId, UUID id) {
        ItemType item = lockItem(id, stores.storeFor(actorId));
        if (!item.isActive()) {
            throw new ItemTypeNotFoundException();
        }
        if (products.existsByStoreIdAndItemTypeId(item.getStoreId(), id)) {
            throw new CatalogConflictException("Item type is used by products.");
        }
        Instant now = Instant.now();
        item.setActive(false, actorId, now);
        history.save(ItemTypeHistory.record(item, actorId, now, ItemTypeHistory.Action.DELETED, item.values(), null));
    }

    @Transactional
    public ItemType reactivate(UUID actorId, UUID id) {
        ItemType item = lockItem(id, stores.storeFor(actorId));
        if (item.isActive()) {
            return item;
        }
        Instant now = Instant.now();
        item.setActive(true, actorId, now);
        history.save(ItemTypeHistory.record(item, actorId, now, ItemTypeHistory.Action.REACTIVATED, null, item.values()));
        return item;
    }

    private ItemType lockItem(UUID id, UUID storeId) {
        return items.lockByIdAndStoreId(id, storeId).orElseThrow(ItemTypeNotFoundException::new);
    }

    private void save(ItemType item) {
        try {
            items.saveAndFlush(item);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_item_type_store_name".equals(violation.getConstraintName())) {
                    throw new ItemTypeNameInUseException();
                }
            }
            throw exception;
        }
    }
}
