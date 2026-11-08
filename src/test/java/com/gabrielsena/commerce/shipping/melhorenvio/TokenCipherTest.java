package com.gabrielsena.commerce.shipping.melhorenvio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenCipherTest {
    @Test
    void encryptsWithUniqueNoncesAndBindsCiphertextToStore() {
        var cipher = new TokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var store = UUID.randomUUID();
        byte[] first = cipher.encrypt(store, "secret-token");
        byte[] second = cipher.encrypt(store, "secret-token");
        assertFalse(Arrays.equals(first, second));
        assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains("secret-token"));
        assertEquals("secret-token", cipher.decrypt(store, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(UUID.randomUUID(), first));
        first[first.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(store, first));
    }

    @Test
    void rejectsInvalidKeys() {
        assertThrows(MelhorEnvioException.class, () -> new TokenCipher(null));
        assertThrows(MelhorEnvioException.class, () -> new TokenCipher("invalid"));
        assertThrows(MelhorEnvioException.class, () -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
