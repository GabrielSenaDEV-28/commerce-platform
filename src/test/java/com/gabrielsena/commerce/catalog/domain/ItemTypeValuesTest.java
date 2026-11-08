package com.gabrielsena.commerce.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemTypeValuesTest {
    @Test
    void normalizesNameAndAllowsOptionalAttributes() {
        assertEquals("Camiseta", new ItemTypeValues(" Camiseta ", false, false, null).name());
        assertEquals(SizeFormat.NUMERIC, new ItemTypeValues("Tênis", true, true, SizeFormat.NUMERIC).sizeFormat());
    }

    @Test
    void rejectsBlankNamesAndInconsistentSizeFormats() {
        assertThrows(IllegalArgumentException.class, () -> new ItemTypeValues(" ", true, false, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemTypeValues("Tipo", true, true, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemTypeValues("Tipo", true, false, SizeFormat.LETTER));
        assertThrows(IllegalArgumentException.class, () -> new ItemTypeValues("x".repeat(121), true, false, null));
    }
}
