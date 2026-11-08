package com.gabrielsena.commerce.shipping.melhorenvio;

import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioClient.Product;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioClient.QuoteRequest;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class MelhorEnvioClientTest {
    private MockRestServiceServer server;
    private MelhorEnvioClient client;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sandbox.melhorenvio.com.br");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MelhorEnvioClient(builder.build(), "Commerce (support@example.test)");
    }

    private QuoteRequest request() {
        return new QuoteRequest("01001000", "96020360", List.of(
                new Product("variant-1", new BigDecimal("10.25"), new BigDecimal("3.00"),
                        new BigDecimal("20.01"), new BigDecimal("0.125"), new BigDecimal("15.50"), 2)));
    }

    @Test
    void sendsUnitDataAndReturnsOnlyCorreiosCustomizedQuotes() {
        server.expect(once(), requestTo("https://sandbox.melhorenvio.com.br/api/v2/me/shipment/calculate"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("User-Agent", "Commerce (support@example.test)"))
                .andExpect(header("Accept", "application/json"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"from":{"postal_code":"01001000"},"to":{"postal_code":"96020360"},
                         "products":[{"id":"variant-1","width":11,"height":3,"length":21,
                         "weight":0.125,"insurance_value":15.50,"quantity":2}]}
                        """))
                .andRespond(withSuccess("""
                        [
                          {"id":1,"name":"PAC","company":{"id":1,"name":"Correios"},
                           "price":"1.00","delivery_time":1,"custom_price":"32.50","custom_delivery_time":9},
                          {"id":2,"name":"SEDEX","company":{"id":1,"name":"Correios"},"error":"Unavailable"},
                          {"id":3,"name":"Other","company":{"id":2,"name":"Other"},
                           "custom_price":"10.00","custom_delivery_time":2}
                        ]
                        """, MediaType.APPLICATION_JSON));
        var result = client.quote("test-token", request());
        assertEquals(1, result.quotes().size());
        assertEquals(new BigDecimal("32.50"), result.quotes().getFirst().price());
        assertEquals(9, result.quotes().getFirst().deliveryDays());
        assertEquals(2, result.unavailable().getFirst().id());
        server.verify();
    }

    @Test
    void keepsTokensPerCallAndSendsMultipleProducts() {
        var multi = new QuoteRequest("01001000", "96020360", List.of(
                request().products().getFirst(),
                new Product("variant-2", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
                        new BigDecimal("0.3"), new BigDecimal("20.00"), 3)));
        for (String token : List.of("first-token", "second-token")) {
            server.expect(requestTo("https://sandbox.melhorenvio.com.br/api/v2/me/shipment/calculate"))
                    .andExpect(header("Authorization", "Bearer " + token))
                    .andExpect(content().json("""
                            {"from":{"postal_code":"01001000"},"to":{"postal_code":"96020360"},
                             "products":[{"id":"variant-1","width":11,"height":3,"length":21,
                             "weight":0.125,"insurance_value":15.50,"quantity":2},
                             {"id":"variant-2","width":1,"height":10,"length":10,
                             "weight":0.3,"insurance_value":20,"quantity":3}]}
                            """))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }
        assertEquals(0, client.quote("first-token", multi).quotes().size());
        assertEquals(0, client.quote("second-token", multi).quotes().size());
        server.verify();
    }

    @Test
    void distinguishesHttpFailuresWithoutExposingTokensOrResponseBodies() {
        for (var status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_GATEWAY, HttpStatus.FOUND)) {
            setup();
            server.expect(requestTo("https://sandbox.melhorenvio.com.br/api/v2/me/shipment/calculate"))
                    .andRespond(withStatus(status).body("test-token confidential provider body"));
            var failure = assertThrows(MelhorEnvioException.class, () -> client.quote("test-token", request()));
            Reason expected = switch (status) {
                case UNAUTHORIZED, FORBIDDEN -> Reason.AUTHENTICATION;
                case TOO_MANY_REQUESTS -> Reason.RATE_LIMIT;
                case BAD_GATEWAY -> Reason.UNAVAILABLE;
                default -> Reason.PROVIDER_REJECTED;
            };
            assertEquals(expected, failure.reason());
            assertFalse(failure.toString().contains("test-token"));
            assertFalse(failure.toString().contains("confidential"));
            assertNull(failure.getCause());
            server.verify();
        }
    }

    @Test
    void reportsNetworkFailureWithoutExposingUnderlyingDetails() {
        server.expect(requestTo("https://sandbox.melhorenvio.com.br/api/v2/me/shipment/calculate"))
                .andRespond(withException(new IOException("test-token")));
        var failure = assertThrows(MelhorEnvioException.class, () -> client.quote("test-token", request()));
        assertEquals(Reason.UNAVAILABLE, failure.reason());
        assertFalse(failure.toString().contains("test-token"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void rejectsMalformedProviderResponsesInsteadOfReturningFakeQuotes() {
        for (String response : List.of("null", "{}", "[{\"id\":1,\"name\":\"PAC\",\"company\":{\"id\":1}}]",
                "[{\"id\":1,\"name\":\"PAC\",\"company\":{\"id\":1},\"custom_price\":\"-1\",\"custom_delivery_time\":2}]",
                "[{\"id\":1,\"name\":\"PAC\",\"company\":{\"id\":1},\"custom_price\":\"bad\",\"custom_delivery_time\":2}]")) {
            setup();
            server.expect(requestTo("https://sandbox.melhorenvio.com.br/api/v2/me/shipment/calculate"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertEquals(Reason.INVALID_RESPONSE,
                    assertThrows(MelhorEnvioException.class, () -> client.quote("test-token", request())).reason());
        }
    }

    @Test
    void validatesInputAndConfigurationWithoutCallingProvider() {
        assertThrows(IllegalArgumentException.class, () -> client.quote("", request()));
        assertThrows(IllegalArgumentException.class, () -> client.quote("test-token", new QuoteRequest("invalid", "96020360", request().products())));
        assertThrows(IllegalArgumentException.class, () -> client.quote("test-token", new QuoteRequest("01001000", "96020360", List.of())));
        assertThrows(IllegalArgumentException.class, () -> client.quote("test-token", new QuoteRequest("01001000", "96020360",
                List.of(new Product("id", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1)))));
        var unconfigured = new MelhorEnvioClient(RestClient.create(), "");
        assertEquals(Reason.CONFIGURATION, assertThrows(MelhorEnvioException.class,
                () -> unconfigured.quote("test-token", request())).reason());
        server.verify();
    }
}
