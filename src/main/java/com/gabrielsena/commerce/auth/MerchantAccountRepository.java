package com.gabrielsena.commerce.auth;

import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MerchantAccountRepository extends JpaRepository<MerchantAccount, UUID> {

    boolean existsByEmail(String email);

    Optional<MerchantAccount> findByEmail(String email);
}
