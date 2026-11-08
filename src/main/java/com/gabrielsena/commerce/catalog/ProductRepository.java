package com.gabrielsena.commerce.catalog;

import com.gabrielsena.commerce.catalog.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsByStoreIdAndItemTypeId(UUID storeId, UUID itemTypeId);
    Page<Product> findByStoreIdAndActive(UUID storeId, boolean active, Pageable pageable);
    Optional<Product> findByIdAndStoreId(UUID id, UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.storeId = :storeId")
    Optional<Product> lockByIdAndStoreId(@Param("id") UUID id, @Param("storeId") UUID storeId);
}
