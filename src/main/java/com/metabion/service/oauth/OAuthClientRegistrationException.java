package com.metabion.service.oauth;

import org.springframework.http.HttpStatus;

public class OAuthClientRegistrationException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final String description;

    public OAuthClientRegistrationException(HttpStatus status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
        this.description = description;
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String description() {
        return description;
    }
}
