package com.metabion.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class InsufficientScopeException extends ResponseStatusException {

    private final String scope;

    public InsufficientScopeException(String scope) {
        super(HttpStatus.FORBIDDEN, "missing scope");
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }
}
