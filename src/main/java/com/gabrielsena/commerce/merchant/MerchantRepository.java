package com.gabrielsena.commerce.merchant;

import com.gabrielsena.commerce.merchant.domain.Merchant;

public interface MerchantRepository {

    Merchant save(Merchant merchant);
}
