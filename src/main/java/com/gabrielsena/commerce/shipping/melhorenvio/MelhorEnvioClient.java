package com.gabrielsena.commerce.shipping.melhorenvio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException.Reason;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MelhorEnvioClient {
    private static final String PATH = "/api/v2/me/shipment/calculate";
    private static final Pattern POSTAL_CODE = Pattern.compile("[0-9]{8}");
    private final RestClient client;
    private final String userAgent;

    public MelhorEnvioClient(RestClient client, String userAgent) {
        this.client = client;
        this.userAgent = userAgent;
    }

    public record Product(String id, BigDecimal widthCm, BigDecimal heightCm, BigDecimal lengthCm,
                          BigDecimal weightKg, BigDecimal unitValue, int quantity) { }
    public record QuoteRequest(String originPostalCode, String destinationPostalCode, List<Product> products) { }
    public record ServiceQuote(int id, String name, BigDecimal price, int deliveryDays) { }
    public record UnavailableService(int id, String name) { }
    public record QuoteResult(List<ServiceQuote> quotes, List<UnavailableService> unavailable) { }

    private record PostalCode(@JsonProperty("postal_code") String value) { }
    private record RequestProduct(String id, int width, int height, int length, BigDecimal weight,
                                  @JsonProperty("insurance_value") BigDecimal insuranceValue, int quantity) { }
    private record ProviderRequest(PostalCode from, PostalCode to, List<RequestProduct> products) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Company(Integer id, String name) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderService(Integer id, String name, Company company,
                                   @JsonProperty("custom_price") BigDecimal customPrice,
                                   @JsonProperty("custom_delivery_time") Integer customDeliveryTime,
                                   String error) { }

    public QuoteResult quote(String accessToken, QuoteRequest request) {
        if (userAgent == null || userAgent.isBlank() || userAgent.indexOf('\n') >= 0 || userAgent.indexOf('\r') >= 0) {
            throw new MelhorEnvioException(Reason.CONFIGURATION);
        }
        if (accessToken == null || accessToken.isBlank() || accessToken.indexOf('\n') >= 0 || accessToken.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A valid access token is required.");
        }
        ProviderRequest body = toProviderRequest(request);
        List<ProviderService> response;
        try {
            response = client.post().uri(PATH).headers(headers -> {
                headers.setBearerAuth(accessToken);
                headers.set("User-Agent", userAgent);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                headers.setContentType(MediaType.APPLICATION_JSON);
            }).body(body).retrieve().onStatus(status -> !status.is2xxSuccessful(), (request1, response1) -> {
                int status = response1.getStatusCode().value();
                throw new MelhorEnvioException(status == 401 || status == 403 ? Reason.AUTHENTICATION
                        : status == 429 ? Reason.RATE_LIMIT : status >= 500 ? Reason.UNAVAILABLE : Reason.PROVIDER_REJECTED);
            }).body(new ParameterizedTypeReference<>() { });
        } catch (ResourceAccessException exception) {
            throw new MelhorEnvioException(Reason.UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new MelhorEnvioException(Reason.INVALID_RESPONSE);
        }
        if (response == null) {
            throw new MelhorEnvioException(Reason.INVALID_RESPONSE);
        }
        List<ServiceQuote> quotes = new ArrayList<>();
        List<UnavailableService> unavailable = new ArrayList<>();
        for (ProviderService service : response) {
            if (service == null || service.id() == null || service.id() <= 0
                    || service.name() == null || service.name().isBlank()
                    || service.company() == null || service.company().id() == null) {
                throw new MelhorEnvioException(Reason.INVALID_RESPONSE);
            }
            if (service.company().id() != 1) {
                continue;
            }
            if (service.error() != null && !service.error().isBlank()) {
                unavailable.add(new UnavailableService(service.id(), service.name()));
            } else if (service.customPrice() == null || service.customPrice().signum() < 0
                    || service.customPrice().scale() > 2 || service.customDeliveryTime() == null
                    || service.customDeliveryTime() < 0) {
                throw new MelhorEnvioException(Reason.INVALID_RESPONSE);
            } else {
                quotes.add(new ServiceQuote(service.id(), service.name(), service.customPrice(), service.customDeliveryTime()));
            }
        }
        return new QuoteResult(List.copyOf(quotes), List.copyOf(unavailable));
    }

    private static ProviderRequest toProviderRequest(QuoteRequest request) {
        if (request == null || request.originPostalCode() == null || request.destinationPostalCode() == null
                || !POSTAL_CODE.matcher(request.originPostalCode()).matches()
                || !POSTAL_CODE.matcher(request.destinationPostalCode()).matches()
                || request.products() == null || request.products().isEmpty()) {
            throw new IllegalArgumentException("Valid postal codes and products are required.");
        }
        List<RequestProduct> products = new ArrayList<>();
        for (Product product : request.products()) {
            if (product == null || product.id() == null || product.id().isBlank() || product.quantity() <= 0
                    || product.weightKg() == null || product.weightKg().signum() <= 0
                    || product.unitValue() == null || product.unitValue().signum() < 0
                    || product.unitValue().stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Invalid product for quotation.");
            }
            products.add(new RequestProduct(product.id(), dimension(product.widthCm()), dimension(product.heightCm()),
                    dimension(product.lengthCm()), product.weightKg(), product.unitValue(), product.quantity()));
        }
        return new ProviderRequest(new PostalCode(request.originPostalCode()), new PostalCode(request.destinationPostalCode()), products);
    }

    private static int dimension(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Invalid product dimension.");
        }
        return value.setScale(0, RoundingMode.CEILING).intValueExact();
    }
}
