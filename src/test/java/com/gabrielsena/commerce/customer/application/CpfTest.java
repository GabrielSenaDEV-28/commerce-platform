package com.gabrielsena.commerce.customer.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class CpfTest {
    @Test
    void normalizesFormattingAndPreservesLeadingZeros() {
        assertEquals("52998224725", Cpf.normalize(" 529.982.247-25 "));
        assertEquals("52998224725", Cpf.normalize("52998224725"));
        assertEquals("01234567890", Cpf.normalize("012.345.678-90"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "00000000000", "111.111.111-11", "52998224724", "52998224735",
            "529 982 247 25", "529.982.24725", "5299822472a", "５２９９８２２４７２５"})
    void rejectsMalformedCpf(String input) {
        assertFalse(Cpf.isValid(input));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(Cpf.Problem.class, () -> Cpf.normalize(input)).getStatusCode());
    }

    @Test
    void requiresNamePhoneAndCpfForCheckout() {
        assertTrue(CustomerProfileService.isCheckoutProfileComplete(profile("Ana", "11987654321", "52998224725")));
        assertFalse(CustomerProfileService.isCheckoutProfileComplete(profile(" ", "11987654321", "52998224725")));
        assertFalse(CustomerProfileService.isCheckoutProfileComplete(profile("Ana", null, "52998224725")));
        assertFalse(CustomerProfileService.isCheckoutProfileComplete(profile("Ana", " ", "52998224725")));
        assertFalse(CustomerProfileService.isCheckoutProfileComplete(profile("Ana", "11987654321", null)));
        assertFalse(CustomerProfileService.isCheckoutProfileComplete(profile("Ana", "11987654321", "11111111111")));
    }

    private static CustomerProfileService.Profile profile(String name, String phone, String cpf) {
        return new CustomerProfileService.Profile(name, "ana@example.test", phone, cpf);
    }
}
