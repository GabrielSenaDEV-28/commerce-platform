package com.gabrielsena.commerce.store.domain;

import com.gabrielsena.commerce.store.InvalidSubdomainException;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Locale;

@Getter
@EqualsAndHashCode
public final class Subdomain {

    private final String value;

    private Subdomain(String value) {
        this.value = value;
    }

    public static Subdomain of(String value) {

        if (value == null || value.isBlank()) {
            throw new InvalidSubdomainException("Subdomain must not be blank");
        }

        String normalized = value.toLowerCase(Locale.ROOT);

        if (normalized.length() > 63
                || !normalized.matches("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")) {
            throw new InvalidSubdomainException("Invalid subdomain format");
        }

        return new Subdomain(normalized);
    }

}
