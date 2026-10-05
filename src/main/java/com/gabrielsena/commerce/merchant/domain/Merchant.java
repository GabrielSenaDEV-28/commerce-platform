package com.gabrielsena.commerce.merchant.domain;

import lombok.Getter;

import java.util.UUID;

@Getter
public class Merchant {

    private final UUID id;
    private final String name;

    private Merchant(UUID id, String name) {
        if (id == null) {
            throw new IllegalArgumentException("Merchant id must not be null");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Merchant name must not be blank");
        }
        this.id = id;
        this.name = name;
    }

    public static Merchant create(String name) {

        return new Merchant(UUID.randomUUID(), name );
    }
}
