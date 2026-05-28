package com.metabion.service;

import com.metabion.domain.User;

public interface MfaChallengeService {
    /** True if a second-factor challenge must be completed before the session is fully authenticated. */
    boolean isRequired(User user);
}
