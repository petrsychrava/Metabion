package com.metabion.config;

import com.metabion.domain.PatientAccessToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.stream.Stream;

public class PatientAccessTokenAuthentication extends AbstractAuthenticationToken {

    private final PatientAccessToken token;

    public PatientAccessTokenAuthentication(PatientAccessToken token) {
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

    public PatientAccessToken token() {
        return token;
    }

    private static Collection<GrantedAuthority> authorities(PatientAccessToken token) {
        var roleAuthorities = token.getUser().roleNames().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role));
        var scopeAuthorities = token.scopes().stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope.authority()));
        return Stream.concat(roleAuthorities, scopeAuthorities).toList();
    }
}
