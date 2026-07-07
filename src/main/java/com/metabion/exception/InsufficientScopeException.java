package com.metabion.exception;

public class InsufficientScopeException extends RuntimeException {

    private final String scope;

    public InsufficientScopeException(String scope) {
        super("Insufficient scope");
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }
}
