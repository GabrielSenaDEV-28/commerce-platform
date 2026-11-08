package com.gabrielsena.commerce.catalog.application;

public interface ProductPhotoStorage {
    void put(String key, String contentType, byte[] contents);
    void delete(String key);
    String url(String key);
}
