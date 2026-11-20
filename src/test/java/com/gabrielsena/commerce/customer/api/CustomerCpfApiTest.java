package com.gabrielsena.commerce.customer.api;

import com.gabrielsena.commerce.api.ApiExceptionHandler;
import com.gabrielsena.commerce.auth.api.StoreSessionController;
import com.gabrielsena.commerce.auth.api.StoreSessionCookies;
import com.gabrielsena.commerce.auth.application.StoreBrowserSessions;
import com.gabrielsena.commerce.auth.application.StoreAccountIdentity;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.customer.application.CustomerProfileService;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerCpfApiTest {
    private static final String EMAIL = "ana@example.test";
    private static final String ACCOUNT_FIELDS = """
            "name":"Ana","email":"ana@example.test","password":"password1234"
            """;
    private static final String PROFILE_FIELDS = """
            "name":"Ana","phone":"11987654321"
            """;
    private final UUID store = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var identities = mock(StoreAccountIdentity.class);
        when(identities.lockForRegistration(store, EMAIL)).thenReturn(new StoreAccountIdentity.Identity(false, false));
        var passwords = mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("hash");
        var stores = mock(PublicStoreResolver.class);
        when(stores.resolve(anyString())).thenReturn(store);
        var auth = spy(new CustomerAuthService(jdbc, passwords, mock(JwtEncoder.class), "test", identities));
        doReturn(new CustomerAuthService.Customer(customer, "Ana", EMAIL)).when(auth).customer(eq(store), isNull());
        var sessions = new StoreBrowserSessions(jdbc, null, auth, null, null, Duration.ofDays(7), Duration.ofDays(30));
        mvc = MockMvcBuilders.standaloneSetup(new CustomerAuthController(auth, stores),
                new StoreSessionController(sessions, stores, new StoreSessionCookies(true)),
                new CustomerProfileController(new CustomerProfileService(jdbc), auth, stores))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/public/customer-auth/register", "/api/public/store-auth/session/register"})
    void requiresCpfAndRejectsBadCheckDigitsInBothRegistrationApis(String endpoint) throws Exception {
        mvc.perform(post(endpoint).contentType(APPLICATION_JSON).content("{" + ACCOUNT_FIELDS + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).contentType(APPLICATION_JSON)
                        .content("{" + ACCOUNT_FIELDS + ",\"cpf\":\"529.982.247-24\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("cpf: Enter a valid CPF."));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/public/customer-auth/register", "/api/public/store-auth/session/register"})
    void returnsCpfConflictWithoutEchoingTheNumber(String endpoint) throws Exception {
        when(jdbc.update(anyString(), any(UUID.class), eq(store), eq("Ana"), eq(EMAIL), eq("hash"), eq("52998224725")))
                .thenThrow(new DuplicateKeyException("fixture"));
        mvc.perform(post(endpoint).contentType(APPLICATION_JSON)
                        .content("{" + ACCOUNT_FIELDS + ",\"cpf\":\"529.982.247-25\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CPF_ALREADY_IN_USE"))
                .andExpect(jsonPath("$.cpf").doesNotExist());
    }

    @Test
    void rejectsMissingAndRepeatedCpfInProfileUpdate() throws Exception {
        var path = "/api/public/customer/profile";
        mvc.perform(put(path).contentType(APPLICATION_JSON).content("{" + PROFILE_FIELDS + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(path).contentType(APPLICATION_JSON)
                        .content("{" + PROFILE_FIELDS + ",\"cpf\":\"111.111.111-11\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("cpf: Enter a valid CPF."));
        verifyNoInteractions(jdbc);
    }

    @Test
    void storesNormalizedCpfWithoutAddingItToRegistrationResponse() throws Exception {
        when(jdbc.update(anyString(), any(UUID.class), eq(store), eq("Ana"), eq(EMAIL), eq("hash"), eq("52998224725")))
                .thenReturn(1);
        mvc.perform(post("/api/public/customer-auth/register").contentType(APPLICATION_JSON)
                        .content("{" + ACCOUNT_FIELDS + ",\"cpf\":\"529.982.247-25\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cpf").doesNotExist());
    }

    @Test
    void profileUpdateReturnsNormalizedCpfAndReportsAStoreConflict() throws Exception {
        when(jdbc.update(anyString(), eq("Ana"), eq("11987654321"), eq("52998224725"), eq(store), eq(customer)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("fixture"));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<CustomerProfileService.Profile>>any(),
                eq(store), eq(customer)))
                .thenReturn(new CustomerProfileService.Profile("Ana", EMAIL, "11987654321", "52998224725"));
        String path = "/api/public/customer/profile";
        String body = "{" + PROFILE_FIELDS + ",\"cpf\":\"529.982.247-25\"}";
        mvc.perform(put(path).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value("52998224725"));
        mvc.perform(put(path).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CPF_ALREADY_IN_USE"));
    }
}
