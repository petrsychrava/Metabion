# Authenticated App Landing Dashboard Design

## Purpose

Design the authenticated `/app` experience as a demo-ready adaptive role dashboard for Metabion.

The public `/` page remains out of scope and can continue redirecting according to authentication state. Public marketing, medical positioning, and consent/legal content are separate design problems.

`/app` should show the eventual product structure while clearly separating implemented workflows from planned roadmap areas. It should no longer feel like an account-only page.

## Current Context

The application is a Spring Boot MVC backend with server-rendered Thymeleaf pages. Authentication, roles, staff invitations, patient onboarding, and clinical onboarding review already exist.

Implemented authenticated routes include:

- `/app`
- `/app/onboarding`
- `/app/onboarding/history`
- `/app/clinical/onboarding`
- `/app/staff-invitations/new`

The new landing dashboard builds on the authenticated app routes and should bring staff invitations into the app route space. It must not introduce routes for unimplemented planned features.

## Product Direction

Use an adaptive role dashboard with a sidebar workbench shell.

Core decisions:

- No role switcher.
- Patient accounts remain separate from staff/admin accounts.
- Staff roles may be combined because physician, nutrition specialist, and coordinator workflows are operationally related.
- Admin is treated as operations-only in the UI.
- Admin utilities remain admin-only.
- Admin does not see patient or clinical dashboard panels only because of broad backend access.
- Users see role-relevant implemented and planned items only.

## Navigation Model

The sidebar is the complete authenticated app menu for the current user.

It is workflow-based, not role-group-based. Menu labels should answer "what can I do?" rather than "what role am I?".

Always visible:

- `Home`
- `Account`

Visible according to role relevance:

- patient workflows for `PATIENT`
- clinical and study-operation workflows for `NUTRITION_SPECIALIST`, `PHYSICIAN`, and `COORDINATOR`
- admin utilities for `ADMIN`

Available items are normal links. Planned items are disabled, not clickable, and include the visible suffix `- planned`.

The sidebar should not use `Patient`, `Clinical`, or `Administration` as first-level role containers in the first version. If navigation grows later, grouping should be by product area, such as `Program`, `Monitoring`, `Study operations`, or `Administration`.

The sidebar should remain visible when users open implemented app workflows from the sidebar or dashboard cards. Linked pages should use the shared workbench shell, with the current menu item visually marked as active.

## Dashboard Model

The `/app` dashboard is built from the same role-aware menu catalog as the sidebar.

The sidebar remains complete navigation. Dashboard cards are a curated subset for emphasis and may later become configurable.

Each catalog item should define:

- label
- route, when implemented
- role relevance
- planned flag
- short dashboard description

Do not introduce durable user-facing status vocabulary such as `Available`, `Restricted`, or `Planned` badges beyond the temporary `- planned` label suffix.

## Patient Items

For `PATIENT` users:

- `Onboarding` -> `/app/onboarding`
- `Onboarding history` -> `/app/onboarding/history`
- `Education library - planned`
- `Daily diet and symptom check-ins - planned`
- `Lab trends - planned`
- `Protocol phase - planned`
- `Red-flag guidance - planned`
- `Patient timeline - planned`

Patient dashboard cards should emphasize the program journey: baseline onboarding, education, daily check-ins, labs, protocol, safety guidance, and timeline.

## Clinical And Coordinator Items

For `NUTRITION_SPECIALIST`, `PHYSICIAN`, and `COORDINATOR` users:

- `Onboarding review` -> `/app/clinical/onboarding`
- `Assigned patient overview - planned`
- `Red-flag monitoring - planned`
- `Data completeness - planned`
- `Protocol checkpoints - planned`
- `Cohort and participant management - planned`
- `Research export and reports - planned`

Clinical/coordinator dashboard cards should emphasize review queues, assigned patients, safety monitoring, missing data, protocol checkpoints, cohort operations, and research outputs.

## Admin Items

For `ADMIN` users:

- `Staff invitations` -> `/app/staff-invitations/new`
- `Content management - planned`
- `Rule configuration - planned`
- `Audit review - planned`

Admin dashboard cards should emphasize operational utilities only. Admin should not receive patient or clinical cards unless that policy is deliberately changed later.

## Web Implementation Shape

Keep the implementation server-rendered and local to the web layer.

Add a small navigation/catalog model, for example:

- `AppMenuItem`
- `AppMenuCatalog`
- optionally `AppDashboardSection` if grouping helps the Thymeleaf template

`AppMenuCatalog` should compute items from authenticated authorities. It should not query clinical data for this phase.

`WebAuthController.GET /app` should populate:

- signed-in email
- roles
- sidebar menu items
- dashboard card items or sections

Existing controllers for onboarding, clinical review, and staff invitations can remain responsible for their business flows, but their templates should render inside the shared workbench shell when reached from the authenticated app menu.

The shared shell should apply to:

- `/app`
- `/app/onboarding`
- `/app/onboarding/history`
- `/app/clinical/onboarding`
- `/app/clinical/onboarding/{id}`
- `/app/staff-invitations/new`

Move the MVC staff invitation UI into the app namespace:

- replace `GET /admin/staff-invitations/new` with `GET /app/staff-invitations/new`
- replace `POST /admin/staff-invitations` with `POST /app/staff-invitations`
- keep `ROLE_ADMIN` authorization for both routes
- do not keep `/admin/staff-invitations/**` as a duplicate UI route unless a later compatibility requirement appears

## Security

Backend security remains the source of truth.

- Existing route permissions stay unchanged except for the MVC staff invitation route moving into the `/app` namespace.
- Staff invitation MVC permissions move from `/admin/staff-invitations/**` to `/app/staff-invitations/**`, still requiring `ROLE_ADMIN`.
- Planned items have no routes.
- Planned items are disabled and not focusable as actions.
- Staff invitation remains admin-only.
- Admin UI remains operations-only.
- No patient/staff role switching is introduced.
- No passwords, tokens, session IDs, credentials, or sensitive clinical data are rendered or logged by the landing page.

## UX Direction

The page should feel like a healthcare/study product workbench, not a marketing page.

Guidelines:

- quiet, dense, practical layout
- persistent sidebar on desktop
- single-column stacked navigation/dashboard on mobile
- clear focus states and keyboard navigation
- sufficient contrast
- 8px or smaller border radius
- no decorative hero
- no fake clinical numbers
- no large promotional copy

Planned items should look intentionally inactive:

- visible label includes `- planned`
- muted visual treatment
- no link target
- not keyboard-focusable as fake actions
- optional short description explaining the future area

Example dashboard descriptions:

- `Complete baseline IBD, medication, and lab context.`
- `Review submitted baselines for assigned participants.`
- `Create staff invitation links for operational users.`

The current green/neutral palette can stay, but the layout should move away from a single centered auth-card feel.

## Testing

Unit tests should cover the menu catalog:

- patient role receives patient implemented and planned items
- clinical staff roles receive clinical/study-operation items
- coordinator receives clinical/study-operation items
- admin receives admin items only
- planned items have no route
- implemented items have expected routes

MVC/security tests should cover:

- authenticated patient `/app` renders onboarding links and patient planned labels
- authenticated patient does not render clinical/admin items
- authenticated clinical user `/app` renders onboarding review and clinical/study planned labels
- authenticated admin `/app` renders staff invitations and admin planned labels only
- authenticated admin can open `/app/staff-invitations/new`
- non-admin users cannot open `/app/staff-invitations/new`
- anonymous `/app` remains rejected by security
- planned items are rendered disabled and non-clickable
- sign-out form still includes CSRF
- implemented app pages reached from the menu render the shared sidebar shell with the active item marked

Full verification should run:

```bash
./gradlew test
```

## Acceptance Criteria

- `/app` presents an adaptive role dashboard rather than an account-only page.
- The sidebar is the complete role-relevant menu for the current user.
- Dashboard cards are a curated subset of the role-relevant catalog.
- Implemented items link to existing routes.
- Planned items are visible for demos, disabled, and labeled with `- planned`.
- Users see only items relevant to their roles.
- Admin sees admin utilities only.
- Staff invitation UI uses `/app/staff-invitations/**` as its canonical MVC route and stays admin-only.
- Implemented app workflow pages preserve the workbench sidebar after navigation.
- No planned-feature routes are introduced.
- Existing auth, onboarding, clinical review, and staff invitation flows keep working.
