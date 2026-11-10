package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.EmailAlreadyRegisteredException;
import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.merchant.application.OnboardMerchant;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegisterMerchantTest {
    @Test
    void shouldCheckLockedStoreForCustomerCollisionBeforeCreatingOwnerAccount() {
        var onboard = mock(OnboardMerchant.class);
        var accounts = mock(MerchantAccountRepository.class);
        var passwords = mock(PasswordEncoder.class);
        var identities = mock(StoreAccountIdentity.class);
        UUID store = UUID.randomUUID();
        when(onboard.execute(any())).thenReturn(
                new OnboardMerchantResult(UUID.randomUUID(), store, "Merchant", "Store", "shop"));
        when(identities.lockForRegistration(store, "owner@example.test"))
                .thenReturn(new StoreAccountIdentity.Identity(false, true));
        var register = new RegisterMerchant(onboard, accounts, passwords, identities);
        assertThrows(EmailAlreadyRegisteredException.class, () -> register.execute(
                new RegisterMerchantCommand("Merchant", "Store", "shop", " OWNER@EXAMPLE.TEST ", "password")));
        verify(identities).lockForRegistration(store, "owner@example.test");
        verify(accounts, never()).saveAndFlush(any());
        verifyNoInteractions(passwords);
    }
}
