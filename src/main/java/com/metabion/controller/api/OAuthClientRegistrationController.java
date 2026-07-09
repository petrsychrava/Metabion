package com.metabion.controller.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.dto.oauth.OAuthClientRegistrationRequest;
import com.metabion.dto.oauth.OAuthClientRegistrationResponse;
import com.metabion.dto.oauth.OAuthErrorResponse;
import com.metabion.service.oauth.OAuthClientRegistrationException;
import com.metabion.service.oauth.OAuthClientRegistrationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class OAuthClientRegistrationController {

    private final OAuthClientRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    public OAuthClientRegistrationController(OAuthClientRegistrationService registrationService,
                                             ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.registrationService = registrationService;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
    }

    @PostMapping(value = "/oauth/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> register(@RequestBody String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > registrationService.maxRequestBytes()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata", "registration request is too large");
        }
        try {
            OAuthClientRegistrationRequest request = objectMapper.readValue(body, OAuthClientRegistrationRequest.class);
            OAuthClientRegistrationResponse response = registrationService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (JsonProcessingException ex) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata", "registration request is invalid JSON");
        } catch (OAuthClientRegistrationException ex) {
            return error(ex.status(), ex.error(), ex.description());
        }
    }

    private ResponseEntity<OAuthErrorResponse> error(HttpStatus status, String error, String description) {
        return ResponseEntity.status(status).body(new OAuthErrorResponse(error, description));
    }
}
