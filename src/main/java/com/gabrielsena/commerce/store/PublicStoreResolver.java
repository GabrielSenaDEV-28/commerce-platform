package com.gabrielsena.commerce.store;

import com.gabrielsena.commerce.store.domain.Subdomain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

@Service
public class PublicStoreResolver {
    private final StoreRepository stores;
    private final String baseDomain;

    public PublicStoreResolver(StoreRepository stores, @Value("${storefront.base-domain}") String baseDomain) {
        this.stores = stores;
        this.baseDomain = baseDomain.toLowerCase(Locale.ROOT);
        if (this.baseDomain.length() > 253 || !this.baseDomain.matches(
                "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")) {
            throw new IllegalArgumentException("Invalid storefront base domain.");
        }
    }

    public UUID resolve(String host) {
        if (host == null) {
            throw notFound();
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        String suffix = "." + baseDomain;
        if (!normalized.endsWith(suffix)) {
            throw notFound();
        }
        String label = normalized.substring(0, normalized.length() - suffix.length());
        Subdomain subdomain;
        try {
            subdomain = Subdomain.of(label);
        } catch (InvalidSubdomainException exception) {
            throw notFound();
        }
        return stores.findBySubdomain(subdomain).orElseThrow(PublicStoreResolver::notFound).getId();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found.");
    }
}
