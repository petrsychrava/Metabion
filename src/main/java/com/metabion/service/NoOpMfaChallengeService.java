package com.metabion.service;

import com.metabion.domain.User;
import org.springframework.stereotype.Component;

@Component
public class NoOpMfaChallengeService implements MfaChallengeService {
    @Override
    public boolean isRequired(User user) {
        return false;
    }
}
