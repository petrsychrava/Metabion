package com.metabion.service;

public class RateLimitedException extends RuntimeException {

    private final String endpoint;

    public RateLimitedException(String endpoint) {
        super("Rate limit exceeded");
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }
}
