package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AuthenticateStoreAccountTest {
    private final StoreAccountIdentity identities = mock(StoreAccountIdentity.class);
    private final AuthenticateMerchant merchants = mock(AuthenticateMerchant.class);
    private final CustomerAuthService customers = mock(CustomerAuthService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthenticateStoreAccount auth = new AuthenticateStoreAccount(identities, merchants, customers, passwords);
    private final UUID store = UUID.randomUUID();

    @Test
    void shouldAuthenticateOnlyResolvedOwnerWithoutCustomerFallback() {
        when(identities.resolve(store, "owner@example.test")).thenReturn(new StoreAccountIdentity.Identity(true, false));
        when(merchants.execute("owner@example.test", "wrong")).thenThrow(new InvalidCredentialsException());
        assertThrows(InvalidCredentialsException.class, () -> auth.execute(store, " OWNER@EXAMPLE.TEST ", "wrong"));
        verify(merchants).execute("owner@example.test", "wrong");
        verifyNoInteractions(customers);
    }

    @Test
    void shouldAuthenticateOnlyResolvedCustomerWithoutMerchantFallback() {
        when(identities.resolve(store, "customer@example.test")).thenReturn(new StoreAccountIdentity.Identity(false, true));
        when(customers.login(store, "customer@example.test", "wrong")).thenThrow(new InvalidCredentialsException());
        assertThrows(InvalidCredentialsException.class, () -> auth.execute(store, "CUSTOMER@EXAMPLE.TEST", "wrong"));
        verify(customers).login(store, "customer@example.test", "wrong");
        verifyNoInteractions(merchants);
    }

    @Test
    void shouldRejectAmbiguityBeforeAnyPasswordAuthentication() {
        when(identities.resolve(store, "legacy@example.test")).thenReturn(new StoreAccountIdentity.Identity(true, true));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> auth.execute(store, "legacy@example.test", "password")).getStatusCode().value());
        verifyNoInteractions(merchants, customers);
    }
}
