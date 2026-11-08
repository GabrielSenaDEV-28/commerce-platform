package com.gabrielsena.commerce.store.domain;

import com.gabrielsena.commerce.store.persistence.SubdomainAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.util.UUID;

@Entity
@Table(
        name = "stores",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stores_merchant", columnNames = "merchant_id"),
                @UniqueConstraint(name = "uk_stores_subdomain", columnNames = "subdomain")
        }
)
@Getter
public class Store {

    @Id
    @Column(name = "store_id", nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Convert(converter = SubdomainAttributeConverter.class)
    @Column(nullable = false, length = 63)
    private Subdomain subdomain;

    protected Store() {
    }

    private Store(
            UUID id,
            UUID merchantId,
            String name,
            Subdomain subdomain
    ) {
        if (id == null) {
            throw new IllegalArgumentException("Store id must not be null");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("Store merchant id must not be null");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Store name must not be null or blank");
        }

        if (subdomain == null) {
            throw new IllegalArgumentException("Store subdomain must not be null");
        }

        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.subdomain = subdomain;
    }

    public static Store create(UUID merchantId, String name, Subdomain subdomain) {
        return new Store(
                UUID.randomUUID(),
                merchantId,
                name,
                subdomain
        );
    }
}
