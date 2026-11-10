package com.gabrielsena.commerce.store.api;

import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.store.StoreRepository;
import com.gabrielsena.commerce.store.domain.Store;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;

@RestController
@RequestMapping("/api/store")
public class MerchantStoreController {
    private final CatalogStoreResolver resolver;
    private final StoreRepository stores;

    public MerchantStoreController(CatalogStoreResolver resolver, StoreRepository stores) {
        this.resolver = resolver;
        this.stores = stores;
    }

    public record StoreInfo(String name, String subdomain) { }

    @GetMapping("/me")
    public ResponseEntity<StoreInfo> store(@AuthenticationPrincipal Jwt jwt) {
        Store store = stores.findById(resolver.storeFor(actor(jwt)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new StoreInfo(store.getName(), store.getSubdomain().getValue()));
    }
}
