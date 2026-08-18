package com.metabion.config;

import com.metabion.domain.McpTokenSubject;
import com.metabion.domain.User;

import java.util.Set;

public interface McpTokenAuthentication {

    McpTokenSubject subject();

    User user();

    Long tokenId();

    String clientLabel();

    Set<String> scopeAuthorities();
}
