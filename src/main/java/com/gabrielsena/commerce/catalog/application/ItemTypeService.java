package com.gabrielsena.commerce.catalog.application;

import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.catalog.ItemTypeHistoryRepository;
import com.gabrielsena.commerce.catalog.ItemTypeNameInUseException;
import com.gabrielsena.commerce.catalog.ItemTypeNotFoundException;
import com.gabrielsena.commerce.catalog.ItemTypeRepository;
import com.gabrielsena.commerce.catalog.domain.ItemType;
import com.gabrielsena.commerce.catalog.domain.ItemTypeHistory;
import com.gabrielsena.commerce.catalog.domain.ItemTypeValues;
import com.gabrielsena.commerce.store.StoreRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class ItemTypeService {
    private final ItemTypeRepository items;
    private final ItemTypeHistoryRepository history;
    private final MerchantAccountRepository accounts;
    private final StoreRepository stores;

    public ItemTypeService(ItemTypeRepository items, ItemTypeHistoryRepository history,
                           MerchantAccountRepository accounts, StoreRepository stores) {
        this.items = items;
        this.history = history;
        this.accounts = accounts;
        this.stores = stores;
    }

    @Transactional
    public ItemType create(UUID actorId, ItemTypeValues values) {
        UUID storeId = storeFor(actorId);
        Instant now = Instant.now();
        ItemType item = ItemType.create(storeId, actorId, values, now);
        save(item);
        history.save(ItemTypeHistory.record(item, actorId, now, ItemTypeHistory.Action.CREATED, null, item.values()));
        return item;
    }

    @Transactional(readOnly = true)
    public ItemType get(UUID actorId, UUID id) {
        return items.findByIdAndStoreId(id, storeFor(actorId)).orElseThrow(ItemTypeNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<ItemType> list(UUID actorId, Pageable pageable) {
        return items.findByStoreId(storeFor(actorId), pageable);
    }

    @Transactional(readOnly = true)
    public Page<ItemTypeHistory> history(UUID actorId, UUID id, Pageable pageable) {
        UUID storeId = storeFor(actorId);
        if (!history.existsByItemTypeIdAndStoreId(id, storeId)) {
            throw new ItemTypeNotFoundException();
        }
        return history.findByItemTypeIdAndStoreId(id, storeId, pageable);
    }

    @Transactional
    public ItemType update(UUID actorId, UUID id, ItemTypeValues values) {
        ItemType item = lockItem(id, storeFor(actorId));
        ItemTypeValues oldValues = item.values();
        if (oldValues.equals(values)) {
            return item;
        }
        Instant now = Instant.now();
        item.update(values, actorId, now);
        save(item);
        history.save(ItemTypeHistory.record(item, actorId, now, ItemTypeHistory.Action.UPDATED, oldValues, item.values()));
        return item;
    }

    @Transactional
    public void delete(UUID actorId, UUID id) {
        ItemType item = lockItem(id, storeFor(actorId));
        history.save(ItemTypeHistory.record(item, actorId, Instant.now(), ItemTypeHistory.Action.DELETED, item.values(), null));
        items.delete(item);
    }

    private ItemType lockItem(UUID id, UUID storeId) {
        return items.lockByIdAndStoreId(id, storeId).orElseThrow(ItemTypeNotFoundException::new);
    }

    private UUID storeFor(UUID actorId) {
        MerchantAccount account = accounts.findById(actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return stores.findByMerchantId(account.getMerchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN))
                .getId();
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
