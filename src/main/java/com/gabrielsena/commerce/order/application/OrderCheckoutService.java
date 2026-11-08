package com.gabrielsena.commerce.order.application;

import com.gabrielsena.commerce.customer.application.CustomerAuthService.Customer;
import com.gabrielsena.commerce.customer.application.CustomerProfileService;
import com.gabrielsena.commerce.order.application.OrderService.FulfillmentType;
import com.gabrielsena.commerce.order.application.OrderService.Order;
import com.gabrielsena.commerce.shipping.application.ShippingEstimateService;
import com.gabrielsena.commerce.shipping.application.ShippingEstimateService.CheckoutQuote;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class OrderCheckoutService {
    private final OrderService orders;
    private final ShippingEstimateService estimates;
    private final CustomerProfileService profiles;

    public OrderCheckoutService(OrderService orders, ShippingEstimateService estimates,
                                CustomerProfileService profiles) {
        this.orders = orders;
        this.estimates = estimates;
        this.profiles = profiles;
    }

    public static class ShippingChanged extends RuntimeException {
        private final CheckoutQuote quote;

        public ShippingChanged(CheckoutQuote quote) {
            super("Shipping quote changed.");
            this.quote = quote;
        }

        public CheckoutQuote quote() { return quote; }
    }

    public Order create(UUID store, String token, UUID reservationId, Customer customer, boolean acceptPartial,
                        UUID addressId, FulfillmentType fulfillmentType, UUID quoteId, Integer serviceId) {
        if (fulfillmentType == FulfillmentType.PICKUP && (quoteId != null || serviceId != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pickup does not use a shipping selection.");
        }
        Order existing = orders.existing(store, token, reservationId, customer, acceptPartial,
                addressId, fulfillmentType, quoteId, serviceId);
        if (existing != null) {
            return existing;
        }
        if (fulfillmentType == FulfillmentType.PICKUP) {
            return orders.create(store, token, reservationId, customer, acceptPartial, addressId, fulfillmentType);
        }
        if (quoteId == null || serviceId == null || serviceId <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping selection is required.");
        }
        estimates.requireActive(store, token, reservationId);
        var address = profiles.get(store, customer.id(), addressId);
        var original = estimates.selection(store, reservationId, quoteId, serviceId);
        if (!address.postalCode().equals(original.postalCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping destination changed; quote again.");
        }
        CheckoutQuote refreshed = estimates.checkout(store, token, reservationId, address.postalCode());
        var current = refreshed.quotes().stream().filter(quote -> quote.id() == serviceId).findFirst();
        if (current.isEmpty() || current.get().price().compareTo(original.service().price()) != 0
                || current.get().deliveryDays() != original.service().deliveryDays()) {
            Order concurrent = orders.existing(store, token, reservationId, customer, acceptPartial,
                    addressId, fulfillmentType, quoteId, serviceId);
            if (concurrent != null) {
                return concurrent;
            }
            throw new ShippingChanged(refreshed);
        }
        return orders.createWithShipping(store, token, reservationId, customer, acceptPartial,
                addressId, quoteId, serviceId, refreshed);
    }
}
