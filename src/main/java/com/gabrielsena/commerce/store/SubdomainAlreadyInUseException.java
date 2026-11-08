package com.gabrielsena.commerce.store;

public class SubdomainAlreadyInUseException extends RuntimeException {

    public SubdomainAlreadyInUseException(String subdomain) {

        super("Subdomain " + subdomain + " already in use.");
    }
}
