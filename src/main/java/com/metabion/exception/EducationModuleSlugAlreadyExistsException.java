package com.metabion.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class EducationModuleSlugAlreadyExistsException extends ResponseStatusException {

    public EducationModuleSlugAlreadyExistsException() {
        super(HttpStatus.BAD_REQUEST, "Education module slug already exists");
    }
}
