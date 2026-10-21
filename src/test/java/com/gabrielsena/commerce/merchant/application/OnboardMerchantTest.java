package com.gabrielsena.commerce.merchant.application;

import com.gabrielsena.commerce.merchant.application.port.out.MerchantRepository;
import com.gabrielsena.commerce.merchant.domain.Merchant;
import com.gabrielsena.commerce.store.application.exception.SubdomainAlreadyInUseException;
import com.gabrielsena.commerce.store.application.port.out.StoreRepository;
import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class OnboardMerchantTest {

    private MerchantRepository merchantRepository;
    private StoreRepository storeRepository;
    private OnboardMerchant onboardMerchant;

    @BeforeEach
    void setup() {
        merchantRepository = mock(MerchantRepository.class);
        storeRepository = mock(StoreRepository.class);

        onboardMerchant = new OnboardMerchant(
                merchantRepository,
                storeRepository);
    }

    @Test
    public void shouldRejectOnboardingWhenSubdomainAlreadyExists() {

        OnboardMerchantCommand command = new OnboardMerchantCommand(
                "Urban Store LTDA",
                "Urban Store",
                "urban-store"
        );

        Subdomain subdomain = Subdomain.of(command.subdomain());

        when(storeRepository.existsBySubdomain(subdomain))
            .thenReturn(true);

        assertThrows(
                SubdomainAlreadyInUseException.class,
                () -> onboardMerchant.execute(command)
        );

        verifyNoInteractions(merchantRepository);
        verify(storeRepository, never()).save(any(Store.class));
    }

    @Test
    public void shouldOnboardMerchantAndStoreSuccessfully() {

        OnboardMerchantCommand command = new OnboardMerchantCommand(
                "Urban Store LTDA",
                "Urban Store",
                "urban-store"
        );

        Subdomain subdomain = Subdomain.of(command.subdomain());

        when(storeRepository.existsBySubdomain(subdomain))
                .thenReturn(false);

        OnboardMerchantResult result = onboardMerchant.execute(command);


        assertNotNull(result.merchantId());
        assertNotNull(result.storeId());

        assertEquals("Urban Store LTDA", result.merchantName());
        assertEquals("Urban Store", result.storeName());
        assertEquals("urban-store", result.subdomain());

        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        ArgumentCaptor<Store> storeCaptor = ArgumentCaptor.forClass(Store.class);

        verify(merchantRepository).save(merchantCaptor.capture());
        verify(storeRepository).save(storeCaptor.capture());

        Merchant savedMerchant = merchantCaptor.getValue();
        Store savedStore = storeCaptor.getValue();

        assertEquals(savedMerchant.getId(), savedStore.getMerchantId());

        assertEquals(result.storeId(), savedStore.getId());
        assertEquals(result.merchantId(), savedMerchant.getId());
    }

    @Test
    public void shouldNotPersistAnythingWhenStoreNameIsBlank() {

        OnboardMerchantCommand command = new OnboardMerchantCommand(
                "Urban Store LTDA",
                "   ",
                "urban-store"
        );

        when(storeRepository.existsBySubdomain(Subdomain.of(command.subdomain())))
                .thenReturn(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> onboardMerchant.execute(command)
        );

        verifyNoInteractions(merchantRepository);
        verify(storeRepository, never()).save(any(Store.class));
    }
}
