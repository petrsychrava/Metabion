# Task 1 Report

Status: complete

Commits created: `f7b047a` - `Add OAuth registered client persistence`

Test summary: `./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest` passed.

Concerns: none.

Report file path: `/Users/petrsychrava/IdeaProjects/Metabion/.worktrees/mcp-oauth-dcr/.superpowers/sdd/task-1-report.md`

## Review Fix

Updated `OAuthRegisteredClient` to validate redirect URIs against the DCR policy, require scopes from `PatientAccessTokenScope`, and store redirect URIs in order with `@OrderColumn`. Adjusted `V16__oauth_dynamic_client_registration.sql` for ordered redirect rows and expanded `OAuthRegisteredClientRepositoryTest` to cover round-trip order, HTTPS acceptance, plain HTTP rejection, missing loopback port rejection, and unsupported scope rejection.

Verification:
- `./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest`
- Result: `BUILD SUCCESSFUL`

## Re-review Fix

Addressed the reviewer notes in `OAuthRegisteredClient` and the repository test:
- constrained `tokenEndpointAuthMethod` to `none` and added coverage rejecting `client_secret_basic`
- allowed IPv6 loopback redirect URIs such as `http://[::1]:49152/callback` and added a focused acceptance test

Verification:
- First run: `./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest`
- First result: `acceptsIpv6LoopbackRedirectUriWithExplicitPort()` failed with `IllegalArgumentException` from `OAuthRegisteredClient`
- Second run: `./gradlew test --tests com.metabion.repository.OAuthRegisteredClientRepositoryTest`
- Second result: `BUILD SUCCESSFUL`
