package com.gabrielsena.commerce.shipping.melhorenvio;

public class MelhorEnvioException extends RuntimeException {
    public enum Reason { CONFIGURATION, AUTHENTICATION, RATE_LIMIT, PROVIDER_REJECTED, UNAVAILABLE, INVALID_RESPONSE }

    private final Reason reason;

    public MelhorEnvioException(Reason reason) {
        super("Melhor Envio quotation failed: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
