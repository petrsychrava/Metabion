package com.metabion.service;

import com.metabion.domain.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
//@ConditionalOnMissingBean(MfaChallengeService.class)
public class NoOpMfaChallengeService implements MfaChallengeService {
    @Override
    public boolean isRequired(User user) {
        return false;
    }
}
