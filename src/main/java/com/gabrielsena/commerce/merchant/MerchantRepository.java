package com.gabrielsena.commerce.merchant;

import com.gabrielsena.commerce.merchant.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
}
