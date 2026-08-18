package com.metabion.config;

import com.metabion.domain.ClinicalAccessToken;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.User;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClinicalAccessTokenAuthentication extends AbstractAuthenticationToken implements McpTokenAuthentication {

    private final ClinicalAccessToken token;

    public ClinicalAccessTokenAuthentication(ClinicalAccessToken token) {
        super(authorities(token));
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return token.getUser();
    }

    @Override
    public String getName() {
        return token.getUser().getEmail();
    }

    @Override
    public McpTokenSubject subject() {
        return McpTokenSubject.CLINICIAN;
    }

    @Override
    public User user() {
        return token.getUser();
    }

    @Override
    public Long tokenId() {
        return token.getId();
    }

    @Override
    public String clientLabel() {
        return token.getDisplayLabel();
    }

    @Override
    public Set<String> scopeAuthorities() {
        return token.scopes().stream()
                .map(scope -> scope.authority())
                .collect(Collectors.toUnmodifiableSet());
    }

    public ClinicalAccessToken token() {
        return token;
    }

    private static Collection<GrantedAuthority> authorities(ClinicalAccessToken token) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        if (token.getUser() == null) {
            throw new IllegalArgumentException("token user is required");
        }
        var roleAuthorities = token.getUser().roleNames().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role));
        var scopeAuthorities = token.scopes().stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope.authority()));
        return Stream.concat(roleAuthorities, scopeAuthorities).toList();
    }
}
