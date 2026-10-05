package com.gabrielsena.commerce.store.domain;

import lombok.Getter;

import java.util.UUID;

@Getter
public class Store {

    private final UUID id;
    private final UUID merchantId;
    private final String name;
    private final Subdomain subdomain;

    private Store(
            UUID id,
            UUID merchantId,
            String name,
            Subdomain subdomain
    ) {

        if(id == null) {
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
