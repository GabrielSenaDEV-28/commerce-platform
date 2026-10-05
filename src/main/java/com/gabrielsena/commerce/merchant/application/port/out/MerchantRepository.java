package com.gabrielsena.commerce.merchant.application.port.out;

import com.gabrielsena.commerce.merchant.domain.Merchant;

public interface MerchantRepository {

    Merchant save(Merchant merchant);
}
