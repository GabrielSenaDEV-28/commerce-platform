package com.gabrielsena.commerce.catalog;

import com.gabrielsena.commerce.catalog.domain.ItemTypeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ItemTypeHistoryRepository extends JpaRepository<ItemTypeHistory, UUID> {
    boolean existsByItemTypeIdAndStoreId(UUID itemTypeId, UUID storeId);

    Page<ItemTypeHistory> findByItemTypeIdAndStoreId(UUID itemTypeId, UUID storeId, Pageable pageable);
}
