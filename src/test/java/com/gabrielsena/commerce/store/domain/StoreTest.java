package com.gabrielsena.commerce.store.domain;

import com.gabrielsena.commerce.merchant.domain.Merchant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class StoreTest {

    @Test
    public void shouldCreateStore() {

        UUID merchantid = UUID.randomUUID();
        Subdomain subdomain = Subdomain.of("urban-store");

        Store store = Store.create(
                merchantid,
                "Urban Store",
                subdomain
        );

        assertNotNull(store.getId());
        assertEquals(merchantid, store.getMerchantId());
        assertEquals("Urban Store", store.getName());
        assertEquals(subdomain, store.getSubdomain());
    }

    @Test
    public void shouldRejectNullMerchantId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> Store.create(
                        null,
                        "Urban Store",
                        Subdomain.of("urban-store")
                ));
    }

    @Test
    public void shouldRejectNullSubdomain() {

        assertThrows(
                IllegalArgumentException.class,
                () -> Store.create(
                        UUID.randomUUID(),
                        "Urban Store",
                        null
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    public void shouldRejectNullOrBlankName(String name) {

        assertThrows(
                IllegalArgumentException.class,
                () -> Store.create(
                        UUID.randomUUID(),
                        name,
                        Subdomain.of("urban-store")
                )
        );

    }
}
