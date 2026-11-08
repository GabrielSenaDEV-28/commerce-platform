package com.gabrielsena.commerce.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

@Entity
@Table(name = "merchants")
@Getter
public class Merchant {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    protected Merchant() {
    }

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

        return new Merchant(UUID.randomUUID(), name);
    }
}
