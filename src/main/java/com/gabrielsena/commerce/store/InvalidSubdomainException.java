package com.gabrielsena.commerce.store;

public class InvalidSubdomainException extends IllegalArgumentException {

    public InvalidSubdomainException(String message) {
        super(message);
    }
}
