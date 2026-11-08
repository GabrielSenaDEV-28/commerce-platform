package com.gabrielsena.commerce.store.persistence;

import com.gabrielsena.commerce.store.domain.Subdomain;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SubdomainAttributeConverter implements AttributeConverter<Subdomain, String> {

    @Override
    public String convertToDatabaseColumn(Subdomain subdomain) {
        return subdomain == null ? null : subdomain.getValue();
    }

    @Override
    public Subdomain convertToEntityAttribute(String value) {
        return value == null ? null : Subdomain.of(value);
    }
}
