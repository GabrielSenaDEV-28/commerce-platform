package com.gabrielsena.commerce.merchant.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MerchantTest {

    @Test
    public void shouldCreateMerchantWithValidName() {

        String name = "Robert Lins";

        Merchant merchant = Merchant.create(name);

        assertNotNull(merchant.getName());
        assertEquals(name, merchant.getName());

    }

    @Test
    public void shouldNotCreateMerchantWithBlankName() {

        String name = "";

        assertThrows(
                IllegalArgumentException.class,
                () -> Merchant.create(name)
        );
    }

    @Test
    public void shouldNotCreateMercahntWithNullName() {

        assertThrows(
                IllegalArgumentException.class,
                () -> Merchant.create(null)
        );
    }
}
