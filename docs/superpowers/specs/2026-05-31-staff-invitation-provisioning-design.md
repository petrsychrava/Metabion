# Staff Invitation Provisioning Design

Date: 2026-05-31

## Goal

Add controlled provisioning for expert and coordinator accounts.

Patient self-registration stays unchanged. Staff accounts are created through administrator invitations, accepted by the invited person through a setup link. The first version supports nutrition specialist, physician, and coordinator provisioning. It does not support public staff self-registration, temporary passwords, or automatic patient-to-staff account merging.

## Existing Context

Metabion already has:

- public patient registration with email verification
- session-based login/logout
- password reset
- `RoleName` for supported roles
- `User`, `UserRole`, `PatientProfile`, `StaffProfile`
- RBAC access checks through `AccessControlService`
- token hashing patterns for verification and password reset
- MVC auth pages and REST auth endpoints

The current patient flow creates a disabled `User`, assigns `PATIENT`, creates `PatientProfile`, and enables the account after email verification. This design does not change that flow.

## Chosen Approach

Use an admin-created invitation table.

An administrator creates a pending staff invitation by entering an email and selecting one or more staff roles. The system stores only a hashed invitation token and emails a setup link. The `User` row is created only after the invitee accepts the invitation and sets a password.

This avoids creating disabled or abandoned staff accounts for mistyped or undeliverable emails. It also prevents administrators from handling staff passwords.

## Alternatives Considered

### Disabled Staff User Until Acceptance

The admin creates a disabled `User` immediately, and the invitation enables it later.

This reuses the patient verification pattern, but it pollutes `users` with abandoned or mistyped staff accounts and makes revoke/reissue behavior messier.

### Admin-Created Temporary Password

The admin creates the account and gives the expert a temporary password.

This is the weakest option. It puts credentials in the admin workflow, complicates audit expectations, and does not match the existing token-based account setup patterns.

## Role Rules

Only authenticated `ADMIN` users can create staff invitations in the first version.

Allowed invitation roles:

- `NUTRITION_SPECIALIST`
- `PHYSICIAN`
- `COORDINATOR`

Disallowed invitation roles:

- `PATIENT`
- `ADMIN`

`PATIENT` is mutually exclusive with staff roles for this first version. A staff invitation cannot be created for an email that already belongs to a patient account. Staff role combinations are allowed, such as `PHYSICIAN + COORDINATOR`.

Admin invitation is intentionally excluded from the first version. Provisioning administrators belongs in a separate bootstrap or stricter approval flow later.

## Data Model

Add `staff_invitations`.

Expected fields:

- `id`
- `email`
- `token_hash`
- `invited_by_user_id`
- `expires_at`
- `accepted_at`
- `revoked_at`
- `created_at`

Add `staff_invitation_roles`.

Expected fields:

- `staff_invitation_id`
- `role`

The child role table must constrain roles to `NUTRITION_SPECIALIST`, `PHYSICIAN`, and `COORDINATOR`.

There must be at most one active invitation per email. Active means:

- `accepted_at IS NULL`
- `revoked_at IS NULL`

Expiration is still checked in service logic because a partial unique index cannot depend on the current time. When an admin reissues an invite, the service revokes any active pending invitation for that email before creating a new one.

## Token Handling

Invitation tokens follow the existing verification/reset-token pattern:

- Generate 32 random bytes with `SecureRandom`.
- Encode the plaintext token as URL-safe Base64 without padding.
- Store only SHA-256 hex in `token_hash`.
- Email the plaintext token once in the setup link.
- Never log the plaintext token.
- Accepting the invitation sets `accepted_at`.
- Reissuing an invitation sets `revoked_at` on previous active invitations for that email.

The default invitation TTL is 7 days.

## Staff Invitation Service

Add `StaffInvitationService`.

Responsibilities:

- normalize invitation email
- validate allowed roles
- reject empty role sets
- enforce `PATIENT` mutual exclusivity
- reject existing staff/admin accounts
- reject disabled or unverified existing accounts
- revoke active pending invitations before reissue
- create invitation and invitation role rows
- hash/store token
- send staff invitation email
- accept valid invitation
- create enabled `User`
- assign invited roles
- create `StaffProfile`
- consume invitation

Invitation creation behavior:

1. Require caller to be an admin at the controller/security boundary.
2. Normalize email.
3. Validate selected roles are non-empty and all staff roles.
4. If a `User` exists with `PATIENT`, reject with admin-facing error.
5. If a `User` exists with staff or admin roles, reject with admin-facing error.
6. If a `User` exists but is disabled, reject with admin-facing error requiring manual resolution.
7. Revoke active pending invitations for the email.
8. Create a new invitation.
9. Send setup email.

Invitation acceptance behavior:

1. Hash the plaintext token.
2. Find invitation by hash.
3. Reject if missing, expired, accepted, or revoked.
4. Validate password, including the existing 72-byte BCrypt limit.
5. Recheck that no `User` exists for the invitation email.
6. Create enabled `User`.
7. Assign invitation roles.
8. Create `StaffProfile`.
9. Set `accepted_at`.

If the email became occupied after invitation creation, acceptance fails with a generic public-facing message.

## Email

Extend `EmailService` with a staff invitation email method.

The invitation link points to the MVC acceptance route:

```text
{app.base-url}/staff-invitations/accept?token=...
```

`LoggingEmailService` must redact the token:

```text
{app.base-url}/staff-invitations/accept?token=<redacted>
```

`SmtpEmailService` sends the real link to the invitee.

## REST API

Add admin invitation endpoint:

```http
POST /api/admin/staff-invitations
```

Request:

```json
{
  "email": "expert@example.com",
  "roles": ["PHYSICIAN", "COORDINATOR"]
}
```

Response:

```json
{
  "status": "ok"
}
```

This endpoint requires `ROLE_ADMIN`.

Add public invitation acceptance endpoint:

```http
POST /api/staff-invitations/accept
```

Request:

```json
{
  "token": "plain-token-from-email",
  "password": "new password"
}
```

Response:

```json
{
  "status": "accepted"
}
```

The accept endpoint is public and is CSRF-ignored by exact path, like the existing public auth/setup API endpoints.

## MVC Flow

Add admin invite page:

```http
GET /admin/staff-invitations/new
POST /admin/staff-invitations
```

The admin form collects email and one or more staff roles. It requires authentication with `ROLE_ADMIN` and uses CSRF.

Add invitation acceptance pages:

```http
GET /staff-invitations/accept?token=...
POST /staff-invitations/accept
```

The GET route renders a password setup form with the token in a hidden field. It does not consume the token. The POST route accepts the invitation through the same service used by the REST endpoint.

The accept routes are public.

## Error Handling

Admin invitation errors are clear because the caller is an authenticated admin:

- invalid email: validation error
- empty roles: validation error
- `PATIENT` role requested: validation error
- unsupported role: validation error
- existing patient email: `This email is already registered as a patient. Staff access requires a separate account.`
- existing staff/admin email: `This email already has staff access.`
- disabled/unverified existing user: `This email belongs to an inactive account and requires manual resolution.`

Invitation acceptance errors are generic because the link is public:

- invalid, expired, revoked, or accepted token: `This invitation link is invalid or expired.`
- email conflict at acceptance time: `This invitation cannot be completed. Contact an administrator.`
- password validation errors can show normal password feedback.

No plaintext tokens, passwords, session identifiers, or credentials may be logged.

## Security

Security configuration must:

- require `ROLE_ADMIN` for `/api/admin/staff-invitations`
- require `ROLE_ADMIN` for `/admin/staff-invitations/**`
- permit public access to `/api/staff-invitations/accept`
- permit public access to `GET /staff-invitations/accept` and `POST /staff-invitations/accept`
- keep CSRF enabled for MVC forms
- ignore CSRF only for the public REST accept endpoint by exact path

No patient role can be added through this flow.

Patient/staff mixed accounts are forbidden in this flow.

## Testing Strategy

Persistence tests:

- invitation stores token hash, not plaintext
- invitation can have multiple allowed staff roles
- invitation rejects unsupported roles at domain or database boundary
- only one active invitation per email after reissue
- accepted and revoked invitations are not active

Service tests:

- admin can create invitation for new email
- invitation rejects `PATIENT`
- invitation rejects `ADMIN`
- invitation rejects empty roles
- invitation rejects existing patient email
- invitation rejects existing staff/admin email
- invitation rejects disabled/unverified existing user
- reissue revokes previous active invite
- accept valid invite creates enabled user
- accept valid invite assigns roles
- accept valid invite creates `StaffProfile`
- accept consumes invite
- expired invite fails
- revoked invite fails
- accepted invite fails
- unknown token fails
- accepting invite fails if email became occupied after invite creation
- password 72-byte limit is enforced

REST/MVC/security tests:

- non-admin cannot create staff invitations
- admin REST invite creates invitation and sends email
- MVC admin form requires admin and CSRF
- public accept GET renders form
- public accept POST accepts valid invite
- public accept POST rejects invalid token
- `/api/staff-invitations/accept` is public
- email link points to MVC accept route
- logging email service redacts staff invitation token

Full verification remains:

```bash
./gradlew test
```

## Scope Boundaries

In scope:

- admin-created staff invitations
- invitation persistence
- invitation token hashing
- invitation email
- REST endpoints
- simple MVC pages
- staff account creation on invite acceptance
- staff role assignment
- `StaffProfile` creation
- patient/staff mutual exclusivity in this flow

Out of scope:

- patient registration redesign
- patient pre-registration table
- admin invitation/bootstrap
- coordinator-created invitations
- merging patient and staff accounts
- persona switching between patient and staff modes
- assignment to cohorts or patients during invitation
- full staff management UI beyond invite/create
- audit event log beyond invitation lifecycle timestamps
