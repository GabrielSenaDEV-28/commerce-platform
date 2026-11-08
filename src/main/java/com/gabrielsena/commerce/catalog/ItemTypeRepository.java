package com.gabrielsena.commerce.catalog;

import com.gabrielsena.commerce.catalog.domain.ItemType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ItemTypeRepository extends JpaRepository<ItemType, UUID> {
    Page<ItemType> findByStoreIdAndActive(UUID storeId, boolean active, Pageable pageable);

    Optional<ItemType> findByIdAndStoreIdAndActiveTrue(UUID id, UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ItemType i where i.id = :id and i.storeId = :storeId")
    Optional<ItemType> lockByIdAndStoreId(@Param("id") UUID id, @Param("storeId") UUID storeId);
}
