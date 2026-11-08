package com.gabrielsena.commerce.catalog;

import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.store.StoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class CatalogStoreResolver {
    private final MerchantAccountRepository accounts;
    private final StoreRepository stores;

    public CatalogStoreResolver(MerchantAccountRepository accounts, StoreRepository stores) {
        this.accounts = accounts;
        this.stores = stores;
    }

    public UUID storeFor(UUID actorId) {
        MerchantAccount account = accounts.findById(actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return stores.findByMerchantId(account.getMerchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN))
                .getId();
    }
}
