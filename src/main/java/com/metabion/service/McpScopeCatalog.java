package com.metabion.service;

import com.metabion.domain.ClinicalAccessTokenScope;
import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.PatientAccessTokenScope;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class McpScopeCatalog {

    private McpScopeCatalog() {
    }

    public record ParsedScopes(McpTokenSubject subjectType, Set<String> authorities) {
    }

    public static ParsedScopes parse(Iterable<String> requested) {
        if (requested == null) {
            throw new IllegalArgumentException("scopes are required");
        }
        var authorities = new LinkedHashSet<String>();
        McpTokenSubject subjectType = null;
        for (var requestedAuthority : requested) {
            if (requestedAuthority == null || requestedAuthority.isBlank()) {
                throw new IllegalArgumentException("scope is required");
            }
            var authority = requestedAuthority.trim();
            var scopeSubject = subjectFor(authority);
            if (scopeSubject == null) {
                throw new IllegalArgumentException("unsupported scope: " + authority);
            }
            if (subjectType == null) {
                subjectType = scopeSubject;
            } else if (subjectType != scopeSubject) {
                throw new IllegalArgumentException("patient and clinician scopes cannot be mixed");
            }
            authorities.add(authority);
        }
        if (subjectType == null) {
            throw new IllegalArgumentException("scopes are required");
        }
        return new ParsedScopes(subjectType, Collections.unmodifiableSet(new LinkedHashSet<>(authorities)));
    }

    public static Set<String> supportedAuthorities() {
        return Arrays.stream(McpTokenSubject.values())
                .flatMap(subject -> subject == McpTokenSubject.PATIENT
                        ? Arrays.stream(PatientAccessTokenScope.values()).map(PatientAccessTokenScope::authority)
                        : Arrays.stream(ClinicalAccessTokenScope.values()).map(ClinicalAccessTokenScope::authority))
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Set<PatientAccessTokenScope> patientScopes(Set<String> authorities) {
        return convertFamily(authorities, McpTokenSubject.PATIENT).stream()
                .map(PatientAccessTokenScope::fromAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Set<ClinicalAccessTokenScope> clinicalScopes(Set<String> authorities) {
        return convertFamily(authorities, McpTokenSubject.CLINICIAN).stream()
                .map(ClinicalAccessTokenScope::fromAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> convertFamily(Set<String> authorities, McpTokenSubject expectedSubject) {
        var parsed = parse(authorities == null ? List.of() : authorities);
        if (parsed.subjectType() != expectedSubject) {
            throw new IllegalArgumentException("unsupported scope family");
        }
        return parsed.authorities();
    }

    private static McpTokenSubject subjectFor(String authority) {
        try {
            PatientAccessTokenScope.fromAuthority(authority);
            return McpTokenSubject.PATIENT;
        } catch (IllegalArgumentException ignored) {
            // fall through to clinician scopes
        }
        try {
            ClinicalAccessTokenScope.fromAuthority(authority);
            return McpTokenSubject.CLINICIAN;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
