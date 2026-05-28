package com.metabion.service;

import com.metabion.domain.User;
import org.springframework.stereotype.Service;

@Service
public class NoOpMfaChallengeService implements MfaChallengeService {
    @Override
    public boolean isRequired(User user) {
        return false;
    }
}
