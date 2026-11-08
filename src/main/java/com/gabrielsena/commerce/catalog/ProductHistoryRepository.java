package com.gabrielsena.commerce.catalog;

import com.gabrielsena.commerce.catalog.domain.ProductHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductHistoryRepository extends JpaRepository<ProductHistory, UUID> {
    Page<ProductHistory> findByStoreIdAndProductId(UUID storeId, UUID productId, Pageable page);
}
