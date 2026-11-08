package com.gabrielsena.commerce.store;

import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;

public interface StoreRepository {

    Store save(Store store);

    boolean existsBySubdomain(Subdomain subdomain);
}
