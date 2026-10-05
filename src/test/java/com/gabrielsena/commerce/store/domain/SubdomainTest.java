package com.gabrielsena.commerce.store.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SubdomainTest {

    @Test
    public void  shouldCreateSubdomainWithValidValue() {

        Subdomain subdomain = Subdomain.of("urban-store");
        assertEquals("urban-store", subdomain.getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-urban",
            "urban-",
            "urban_store",
            "urban store",
            "urban@store"
    })
    public void shouldRejectInvalidSubdomainFormat(String value) {
        assertThrows(
            IllegalArgumentException.class,
                () -> Subdomain.of(value)
        );
    }

    @Test
    public void shouldNormalizeSubdomainToLowerCase() {
        Subdomain subdomain = Subdomain.of("URBAN-STORE");

        assertEquals("urban-store", subdomain.getValue());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    public void shouldRejectNullOrBlankSubdomain(String value) {

        assertThrows(
                IllegalArgumentException.class,
                () -> Subdomain.of(value)
        );
    }

}
