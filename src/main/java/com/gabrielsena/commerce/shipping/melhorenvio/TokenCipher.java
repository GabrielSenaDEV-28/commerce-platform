package com.gabrielsena.commerce.shipping.melhorenvio;

import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException.Reason;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class TokenCipher {
    private static final int NONCE_LENGTH = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new MelhorEnvioException(Reason.CONFIGURATION);
        }
        if (bytes.length != 32) {
            throw new MelhorEnvioException(Reason.CONFIGURATION);
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    public byte[] encrypt(UUID storeId, String token) {
        if (storeId == null || token == null || token.isBlank()) {
            throw new IllegalArgumentException("Invalid token or store.");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, storeId);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encrypt integration credentials.");
        }
    }

    public String decrypt(UUID storeId, byte[] ciphertext) {
        if (storeId == null || ciphertext == null || ciphertext.length < NONCE_LENGTH + 16) {
            throw new IllegalStateException("Invalid encrypted integration credentials.");
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(ciphertext);
            byte[] nonce = new byte[NONCE_LENGTH];
            data.get(nonce);
            byte[] encrypted = new byte[data.remaining()];
            data.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, storeId).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot decrypt integration credentials.");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID storeId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(storeId.toString().getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }
}
