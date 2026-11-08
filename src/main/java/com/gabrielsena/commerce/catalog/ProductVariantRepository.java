package com.gabrielsena.commerce.catalog;

import com.gabrielsena.commerce.catalog.domain.ProductVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {
    Page<ProductVariant> findByStoreIdAndProductIdAndActive(UUID storeId, UUID productId, boolean active, Pageable page);
    Optional<ProductVariant> findByIdAndStoreIdAndProductId(UUID id, UUID storeId, UUID productId);
}
