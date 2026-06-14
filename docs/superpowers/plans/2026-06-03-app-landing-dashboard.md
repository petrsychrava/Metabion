# Authenticated App Landing Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the account-only `/app` page with a demo-ready role-aware dashboard and shared authenticated workbench shell.

**Architecture:** Add a small web-layer menu catalog that derives role-relevant implemented and planned items from Spring Security authorities. Render `/app` and linked authenticated workflows inside a shared Thymeleaf workbench shell with persistent sidebar navigation. Move the staff invitation MVC UI from `/admin/staff-invitations/**` to `/app/staff-invitations/**` while keeping `ROLE_ADMIN` authorization.

**Tech Stack:** Java 25, Spring Boot MVC, Spring Security, Thymeleaf, JUnit, MockMvc, Gradle.

---

## Files

Create:

- `src/main/java/com/metabion/controller/web/AppMenuItem.java`: immutable menu/dashboard item metadata.
- `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`: role-aware catalog for sidebar and dashboard items.
- `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`: focused unit tests for role-to-item mapping.

Modify:

- `src/main/java/com/metabion/controller/web/WebAuthController.java`: inject `AppMenuCatalog`, expose menu/dashboard model attributes for `/app`.
- `src/main/java/com/metabion/controller/web/WebOnboardingController.java`: expose menu model attributes and active path for shared shell pages.
- `src/main/java/com/metabion/controller/web/StaffInvitationWebController.java`: move admin invitation MVC routes to `/app/staff-invitations/**`, expose menu model attributes.
- `src/main/java/com/metabion/config/SecurityConfig.java`: replace `/admin/staff-invitations/**` MVC authorization with `/app/staff-invitations/**`.
- `src/main/resources/templates/layout.html`: add shared app shell and sidebar fragments.
- `src/main/resources/templates/app.html`: render adaptive dashboard cards inside shared shell.
- `src/main/resources/templates/onboarding.html`: render inside shared shell with `Onboarding` active.
- `src/main/resources/templates/onboarding-history.html`: render inside shared shell with `Onboarding history` active.
- `src/main/resources/templates/clinical-onboarding.html`: render inside shared shell with `Onboarding review` active.
- `src/main/resources/templates/clinical-onboarding-detail.html`: render inside shared shell with `Onboarding review` active.
- `src/main/resources/templates/admin-staff-invitation.html`: render inside shared shell and post to `/app/staff-invitations`.
- `src/main/resources/static/css/app.css`: add workbench/sidebar/dashboard styles.
- `src/test/java/com/metabion/controller/web/WebAuthControllerTest.java`: assert `/app` role-aware menu/dashboard model.
- `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`: assert rendered dashboard/sidebar content and new staff invitation route.
- `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`: assert linked app pages render shared sidebar shell.
- `src/test/java/com/metabion/controller/web/StaffInvitationWebControllerTest.java`: update staff invitation MVC route tests.
- `src/test/java/com/metabion/config/SecurityConfigTest.java`: update MVC staff invitation security coverage if this file contains `/admin/staff-invitations/**` expectations.

Do not modify:

- `/api/admin/staff-invitations`: REST route stays unchanged.
- `/staff-invitations/accept`: public invitation acceptance route stays unchanged.

---

### Task 1: Add Role-Aware Menu Catalog

**Files:**

- Create: `src/main/java/com/metabion/controller/web/AppMenuItem.java`
- Create: `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`
- Create: `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`

- [ ] **Step 1: Write failing catalog tests**

Create `src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java`:

```java
package com.metabion.controller.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class AppMenuCatalogTest {

    private final AppMenuCatalog catalog = new AppMenuCatalog();

    @Test
    void patientReceivesPatientImplementedAndPlannedItems() {
        var auth = auth("patient@example.com", "ROLE_PATIENT");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Onboarding",
                        "Onboarding history",
                        "Education library - planned",
                        "Daily diet and symptom check-ins - planned",
                        "Lab trends - planned",
                        "Protocol phase - planned",
                        "Red-flag guidance - planned",
                        "Patient timeline - planned",
                        "Account");

        assertThat(catalog.sidebarItems(auth))
                .filteredOn(AppMenuItem::planned)
                .allSatisfy(item -> assertThat(item.route()).isNull());
    }

    @Test
    void clinicalStaffReceivesClinicalAndStudyItems() {
        var auth = auth("doctor@example.com", "ROLE_PHYSICIAN");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Onboarding review",
                        "Assigned patient overview - planned",
                        "Red-flag monitoring - planned",
                        "Data completeness - planned",
                        "Protocol checkpoints - planned",
                        "Cohort and participant management - planned",
                        "Research export and reports - planned",
                        "Account");
    }

    @Test
    void coordinatorReceivesClinicalAndStudyItems() {
        var auth = auth("coordinator@example.com", "ROLE_COORDINATOR");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Onboarding review",
                        "Assigned patient overview - planned",
                        "Red-flag monitoring - planned",
                        "Data completeness - planned",
                        "Protocol checkpoints - planned",
                        "Cohort and participant management - planned",
                        "Research export and reports - planned",
                        "Account");
    }

    @Test
    void adminReceivesAdminItemsOnly() {
        var auth = auth("admin@example.com", "ROLE_ADMIN");

        assertThat(catalog.sidebarItems(auth))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Home",
                        "Staff invitations",
                        "Content management - planned",
                        "Rule configuration - planned",
                        "Audit review - planned",
                        "Account");
    }

    @Test
    void implementedItemsHaveExpectedRoutes() {
        var patient = auth("patient@example.com", "ROLE_PATIENT");
        var clinician = auth("doctor@example.com", "ROLE_NUTRITION_SPECIALIST");
        var admin = auth("admin@example.com", "ROLE_ADMIN");

        assertThat(catalog.sidebarItems(patient))
                .filteredOn(item -> "Onboarding".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/onboarding");
        assertThat(catalog.sidebarItems(clinician))
                .filteredOn(item -> "Onboarding review".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/clinical/onboarding");
        assertThat(catalog.sidebarItems(admin))
                .filteredOn(item -> "Staff invitations".equals(item.label()))
                .singleElement()
                .extracting(AppMenuItem::route)
                .isEqualTo("/app/staff-invitations/new");
    }

    @Test
    void dashboardItemsAreCuratedSubsetOfSidebarItems() {
        var patient = auth("patient@example.com", "ROLE_PATIENT");

        assertThat(catalog.dashboardItems(patient))
                .extracting(AppMenuItem::displayLabel)
                .containsExactly(
                        "Onboarding",
                        "Education library - planned",
                        "Daily diet and symptom check-ins - planned",
                        "Lab trends - planned",
                        "Red-flag guidance - planned");
    }

    private TestingAuthenticationToken auth(String name, String... authorities) {
        var auth = new TestingAuthenticationToken(name, "password", authorities);
        auth.setAuthenticated(true);
        return auth;
    }
}
```

- [ ] **Step 2: Run catalog tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.AppMenuCatalogTest
```

Expected: FAIL because `AppMenuCatalog` and `AppMenuItem` do not exist.

- [ ] **Step 3: Implement `AppMenuItem`**

Create `src/main/java/com/metabion/controller/web/AppMenuItem.java`:

```java
package com.metabion.controller.web;

public record AppMenuItem(
        String label,
        String route,
        boolean planned,
        boolean dashboard,
        String description
) {

    public String displayLabel() {
        return planned ? label + " - planned" : label;
    }

    public boolean linked() {
        return route != null && !planned;
    }
}
```

- [ ] **Step 4: Implement `AppMenuCatalog`**

Create `src/main/java/com/metabion/controller/web/AppMenuCatalog.java`:

```java
package com.metabion.controller.web;

import com.metabion.domain.RoleName;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class AppMenuCatalog {

    public List<AppMenuItem> sidebarItems(Authentication authentication) {
        var roles = roles(authentication);
        var items = new ArrayList<AppMenuItem>();
        items.add(item("Home", "/app", false, false, "Return to the app dashboard."));
        addRoleItems(items, roles);
        items.add(item("Account", null, false, false, "Signed-in account and sign-out."));
        return List.copyOf(items);
    }

    public List<AppMenuItem> dashboardItems(Authentication authentication) {
        return sidebarItems(authentication).stream()
                .filter(AppMenuItem::dashboard)
                .toList();
    }

    private void addRoleItems(List<AppMenuItem> items, Set<RoleName> roles) {
        if (roles.contains(RoleName.PATIENT)) {
            items.add(item("Onboarding", "/app/onboarding", false, true,
                    "Complete baseline IBD, medication, and lab context."));
            items.add(item("Onboarding history", "/app/onboarding/history", false, false,
                    "Review prior baseline submissions."));
            items.add(item("Education library", null, true, true,
                    "IBD and ketogenic nutrition modules, safety basics, hydration, electrolytes, and flare guidance."));
            items.add(item("Daily diet and symptom check-ins", null, true, true,
                    "Track adherence, meals, symptoms, stool data, wellbeing, and suspected flare markers."));
            items.add(item("Lab trends", null, true, true,
                    "Follow CRP, fecal calprotectin, hemoglobin, albumin, and related markers over time."));
            items.add(item("Protocol phase", null, true, false,
                    "View preparation, adaptation, stabilization, and follow-up instructions."));
            items.add(item("Red-flag guidance", null, true, true,
                    "See safety guidance for concerning symptoms or values."));
            items.add(item("Patient timeline", null, true, false,
                    "Review onboarding, logs, labs, alerts, and protocol events chronologically."));
        }
        if (hasClinicalRole(roles)) {
            items.add(item("Onboarding review", "/app/clinical/onboarding", false, true,
                    "Review submitted baselines for assigned participants."));
            items.add(item("Assigned patient overview", null, true, true,
                    "Monitor assigned participants, adherence, trends, risk signals, and phase status."));
            items.add(item("Red-flag monitoring", null, true, true,
                    "Review concerning symptom patterns, lab values, and escalation events."));
            items.add(item("Data completeness", null, true, true,
                    "Find missing or overdue participant data for study follow-up."));
            items.add(item("Protocol checkpoints", null, true, false,
                    "Track scheduled visits, program phases, and required checkpoint tasks."));
            items.add(item("Cohort and participant management", null, true, true,
                    "Manage cohort membership, participant lifecycle status, and operational study progress."));
            items.add(item("Research export and reports", null, true, true,
                    "Prepare pseudonymized exports, data dictionaries, and aggregate reports."));
        }
        if (roles.contains(RoleName.ADMIN)) {
            items.add(item("Staff invitations", "/app/staff-invitations/new", false, true,
                    "Create staff invitation links for operational users."));
            items.add(item("Content management", null, true, true,
                    "Manage reviewed education modules and publication state."));
            items.add(item("Rule configuration", null, true, true,
                    "Manage controlled screening and red-flag rule versions."));
            items.add(item("Audit review", null, true, true,
                    "Review sensitive actions and operational audit history."));
        }
    }

    private boolean hasClinicalRole(Set<RoleName> roles) {
        return roles.contains(RoleName.NUTRITION_SPECIALIST)
                || roles.contains(RoleName.PHYSICIAN)
                || roles.contains(RoleName.COORDINATOR);
    }

    private AppMenuItem item(String label, String route, boolean planned, boolean dashboard, String description) {
        return new AppMenuItem(label, route, planned, dashboard, description);
    }

    private Set<RoleName> roles(Authentication authentication) {
        var roles = EnumSet.noneOf(RoleName.class);
        if (authentication == null) {
            return roles;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            var value = authority.getAuthority();
            if (value != null && value.startsWith("ROLE_")) {
                roles.add(RoleName.from(value.substring("ROLE_".length())));
            }
        }
        return roles;
    }
}
```

- [ ] **Step 5: Run catalog tests and verify they pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.AppMenuCatalogTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/metabion/controller/web/AppMenuItem.java src/main/java/com/metabion/controller/web/AppMenuCatalog.java src/test/java/com/metabion/controller/web/AppMenuCatalogTest.java
git commit -m "Add app menu catalog"
```

---

### Task 2: Wire Menu Catalog Into `/app`

**Files:**

- Modify: `src/main/java/com/metabion/controller/web/WebAuthController.java`
- Modify: `src/test/java/com/metabion/controller/web/WebAuthControllerTest.java`

- [ ] **Step 1: Write failing `/app` model tests**

In `src/test/java/com/metabion/controller/web/WebAuthControllerTest.java`, update the existing `app_renders_authenticated_shell` test and add role-specific tests:

```java
    @Test
    void app_renders_authenticated_shell() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PHYSICIAN", "ROLE_COORDINATOR");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("app"))
                .andExpect(model().attribute("email", "user@example.com"))
                .andExpect(model().attribute("roles", List.of("COORDINATOR", "PHYSICIAN")))
                .andExpect(model().attributeExists("appMenuItems", "dashboardItems", "activePath"));
    }

    @Test
    void app_model_for_patient_contains_patient_items_only() throws Exception {
        var auth = new TestingAuthenticationToken("patient@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activePath", "/app"))
                .andExpect(model().attribute("appMenuItems", org.hamcrest.Matchers.hasItems(
                        new AppMenuItem("Onboarding", "/app/onboarding", false, true,
                                "Complete baseline IBD, medication, and lab context."),
                        new AppMenuItem("Education library", null, true, true,
                                "IBD and ketogenic nutrition modules, safety basics, hydration, electrolytes, and flare guidance."))));
    }

    @Test
    void app_model_for_admin_contains_admin_items_only() throws Exception {
        var auth = new TestingAuthenticationToken("admin@example.com", "password", "ROLE_ADMIN");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth))
                .andExpect(status().isOk())
                .andExpect(model().attribute("appMenuItems", org.hamcrest.Matchers.hasItems(
                        new AppMenuItem("Staff invitations", "/app/staff-invitations/new", false, true,
                                "Create staff invitation links for operational users."),
                        new AppMenuItem("Audit review", null, true, true,
                                "Review sensitive actions and operational audit history."))));
    }
```

Add this import if not present:

```java
import org.hamcrest.Matchers;
```

If the file uses fully qualified `org.hamcrest.Matchers` as shown, no new import is required.

- [ ] **Step 2: Run `/app` controller tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthControllerTest
```

Expected: FAIL because `WebAuthController` does not expose `appMenuItems`, `dashboardItems`, or `activePath`.

- [ ] **Step 3: Inject catalog and populate `/app` model**

Modify the constructor and fields in `src/main/java/com/metabion/controller/web/WebAuthController.java`:

```java
    private final UserService userService;
    private final SecurityService securityService;
    private final AppMenuCatalog appMenuCatalog;

    public WebAuthController(UserService userService, SecurityService securityService, AppMenuCatalog appMenuCatalog) {
        this.userService = userService;
        this.securityService = securityService;
        this.appMenuCatalog = appMenuCatalog;
    }
```

Replace the existing `app(...)` method with:

```java
    @GetMapping("/app")
    public String app(Authentication authentication, Model model) {
        model.addAttribute("email", authentication.getName());
        model.addAttribute("roles", roles(authentication));
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("dashboardItems", appMenuCatalog.dashboardItems(authentication));
        model.addAttribute("activePath", "/app");
        return "app";
    }
```

- [ ] **Step 4: Run `/app` controller tests and verify they pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthControllerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/metabion/controller/web/WebAuthController.java src/test/java/com/metabion/controller/web/WebAuthControllerTest.java
git commit -m "Expose app menu model"
```

---

### Task 3: Move Staff Invitation MVC Routes Into `/app`

**Files:**

- Modify: `src/main/java/com/metabion/controller/web/StaffInvitationWebController.java`
- Modify: `src/main/java/com/metabion/config/SecurityConfig.java`
- Modify: `src/main/resources/templates/admin-staff-invitation.html`
- Modify: `src/test/java/com/metabion/controller/web/StaffInvitationWebControllerTest.java`
- Modify: `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`
- Modify: `src/test/java/com/metabion/config/SecurityConfigTest.java`, only if it asserts MVC staff invitation routes

- [ ] **Step 1: Update failing staff invitation route tests**

In `src/test/java/com/metabion/controller/web/StaffInvitationWebControllerTest.java`, replace these request paths:

```java
get("/admin/staff-invitations/new")
post("/admin/staff-invitations")
```

with:

```java
get("/app/staff-invitations/new")
post("/app/staff-invitations")
```

Add one test proving the old MVC route is gone:

```java
    @Test
    void old_admin_invite_form_route_is_not_available() throws Exception {
        mvc.perform(get("/admin/staff-invitations/new")
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
```

In `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`, replace:

```java
mvc.perform(get("/admin/staff-invitations/new").principal(auth).with(csrf()))
```

with:

```java
mvc.perform(get("/app/staff-invitations/new").principal(auth).with(csrf()))
```

If `src/test/java/com/metabion/config/SecurityConfigTest.java` contains MVC expectations for `/admin/staff-invitations/**`, update them to `/app/staff-invitations/**`. Do not change `/api/admin/staff-invitations` tests.

- [ ] **Step 2: Run staff invitation web tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.StaffInvitationWebControllerTest --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: FAIL because the controller and template still use `/admin/staff-invitations/**`.

- [ ] **Step 3: Move controller mappings and form action**

In `src/main/java/com/metabion/controller/web/StaffInvitationWebController.java`, replace:

```java
    @GetMapping("/admin/staff-invitations/new")
```

with:

```java
    @GetMapping("/app/staff-invitations/new")
```

Replace:

```java
    @PostMapping("/admin/staff-invitations")
```

with:

```java
    @PostMapping("/app/staff-invitations")
```

In `src/main/resources/templates/admin-staff-invitation.html`, replace:

```html
<form th:action="@{/admin/staff-invitations}" th:object="${form}" method="post" class="form">
```

with:

```html
<form th:action="@{/app/staff-invitations}" th:object="${form}" method="post" class="form">
```

- [ ] **Step 4: Update MVC security route**

In `src/main/java/com/metabion/config/SecurityConfig.java`, replace:

```java
.requestMatchers("/admin/staff-invitations/**").hasRole("ADMIN")
```

with:

```java
.requestMatchers("/app/staff-invitations/**").hasRole("ADMIN")
```

Keep this API matcher unchanged:

```java
.requestMatchers(HttpMethod.POST, "/api/admin/staff-invitations").hasRole("ADMIN")
```

- [ ] **Step 5: Run staff invitation route tests and verify they pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.StaffInvitationWebControllerTest --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: PASS.

- [ ] **Step 6: Run security config tests if updated**

Run:

```bash
./gradlew test --tests com.metabion.config.SecurityConfigTest
```

Expected: PASS. If no MVC staff invitation assertions were changed in that file, this still verifies the API admin invitation route stayed protected.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/controller/web/StaffInvitationWebController.java src/main/java/com/metabion/config/SecurityConfig.java src/main/resources/templates/admin-staff-invitation.html src/test/java/com/metabion/controller/web/StaffInvitationWebControllerTest.java src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Move staff invitations into app routes"
```

---

### Task 4: Add Shared Workbench Shell Fragments And Styles

**Files:**

- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: Add app shell fragments**

Replace `src/main/resources/templates/layout.html` with:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org" th:fragment="appShell(pageTitle, activePath, content)">
<head th:fragment="head(pageTitle)">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title th:text="${pageTitle}">Metabion</title>
    <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<header class="brand" th:fragment="brand">
    <a th:href="@{/}" aria-label="Metabion home">Metabion</a>
</header>

<div class="workbench">
    <aside class="sidebar" aria-label="Application navigation">
        <a class="sidebar-brand" th:href="@{/app}">Metabion</a>
        <nav class="sidebar-nav">
            <ul>
                <li th:each="item : ${appMenuItems}">
                    <a th:if="${item.linked()}"
                       th:href="@{${item.route()}}"
                       th:classappend="${item.route() == activePath} ? ' active' : ''"
                       th:text="${item.displayLabel()}">Menu item</a>
                    <span th:unless="${item.linked()}"
                          class="disabled"
                          th:text="${item.displayLabel()}">Menu item - planned</span>
                </li>
            </ul>
        </nav>
        <form class="sidebar-logout" th:action="@{/logout}" method="post">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
            <button type="submit" class="secondary">Sign out</button>
        </form>
    </aside>
    <main class="workbench-main" th:insert="${content}"></main>
</div>
</body>
</html>
```

- [ ] **Step 2: Add workbench CSS**

Append to `src/main/resources/static/css/app.css`:

```css
.workbench {
    display: grid;
    grid-template-columns: 260px minmax(0, 1fr);
    min-height: 100vh;
}

.sidebar {
    background: #ffffff;
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    gap: 24px;
    padding: 24px 18px;
}

.sidebar-brand {
    color: var(--accent-dark);
    font-size: 1.05rem;
    font-weight: 800;
    text-decoration: none;
}

.sidebar-nav ul {
    display: grid;
    gap: 4px;
    list-style: none;
    margin: 0;
    padding: 0;
}

.sidebar-nav a,
.sidebar-nav .disabled {
    border-radius: 6px;
    display: block;
    padding: 8px 10px;
    text-decoration: none;
}

.sidebar-nav a {
    color: var(--text);
}

.sidebar-nav a:hover,
.sidebar-nav a.active {
    background: #e7eee9;
    color: var(--accent-dark);
}

.sidebar-nav .disabled {
    color: var(--muted);
}

.sidebar-logout {
    margin-top: auto;
}

.workbench-main {
    min-width: 0;
    padding: 32px 24px;
}

.dashboard-grid {
    display: grid;
    gap: 16px;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.dashboard-card {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 8px;
    color: var(--text);
    display: block;
    padding: 18px;
    text-decoration: none;
}

.dashboard-card.disabled {
    color: var(--muted);
}

.dashboard-card h2 {
    font-size: 1.05rem;
}

@media (max-width: 760px) {
    .workbench {
        grid-template-columns: 1fr;
    }

    .sidebar {
        border-bottom: 1px solid var(--border);
        border-right: 0;
    }

    .sidebar-logout {
        margin-top: 0;
    }
}
```

- [ ] **Step 3: Run existing template tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: PASS. The shell fragment is available but no page uses it yet.

- [ ] **Step 4: Commit shell fragment and CSS**

```bash
git add src/main/resources/templates/layout.html src/main/resources/static/css/app.css
git commit -m "Add authenticated workbench shell"
```

---

### Task 5: Render `/app` Dashboard In Shared Shell

**Files:**

- Modify: `src/main/resources/templates/app.html`
- Modify: `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`

- [ ] **Step 1: Add dashboard and shell rendering assertions**

In `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`, replace `app_template_renders_authenticated_shell` with:

```java
    @Test
    void app_template_renders_authenticated_workbench_shell() throws Exception {
        var auth = new TestingAuthenticationToken("user@example.com", "password", "ROLE_PATIENT");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"workbench\"")))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Onboarding")))
                .andExpect(content().string(containsString("Education library - planned")))
                .andExpect(content().string(containsString("user@example.com")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }
```

In `src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java`, add:

```java
    @Test
    void admin_app_template_renders_admin_dashboard_items() throws Exception {
        var auth = new TestingAuthenticationToken("admin@example.com", "password", "ROLE_ADMIN");
        auth.setAuthenticated(true);

        mvc.perform(get("/app").principal(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Staff invitations")))
                .andExpect(content().string(containsString("/app/staff-invitations/new")))
                .andExpect(content().string(containsString("Content management - planned")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Onboarding review"))));
    }
```

- [ ] **Step 2: Run `/app` template tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: FAIL because `app.html` still renders the old account panel.

- [ ] **Step 3: Replace `app.html`**

Replace `src/main/resources/templates/app.html` with:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell('Metabion App', ${activePath}, ~{::section})}">
<section>
    <header class="app-header">
        <div>
            <p class="eyebrow">Metabion</p>
            <h1>Dashboard</h1>
            <p class="muted">Signed in as <strong th:text="${email}">user@example.com</strong>.</p>
        </div>
    </header>

    <section class="panel app-panel">
        <h2>Account</h2>
        <p class="muted">
            Roles:
            <span th:if="${#lists.isEmpty(roles)}">none</span>
            <span th:each="role, stat : ${roles}">
                <span th:text="${role}">PATIENT</span><span th:if="${!stat.last}">, </span>
            </span>
        </p>
    </section>

    <section class="dashboard-grid" aria-label="Dashboard">
        <article th:each="item : ${dashboardItems}">
            <a th:if="${item.linked()}"
               class="dashboard-card"
               th:href="@{${item.route()}}">
                <h2 th:text="${item.displayLabel()}">Onboarding</h2>
                <p th:text="${item.description()}">Complete baseline context.</p>
            </a>
            <div th:unless="${item.linked()}" class="dashboard-card disabled">
                <h2 th:text="${item.displayLabel()}">Education library - planned</h2>
                <p th:text="${item.description()}">Future area.</p>
            </div>
        </article>
    </section>
</section>
</html>
```

- [ ] **Step 4: Run `/app` template tests and verify they pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebAuthTemplateTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/app.html src/test/java/com/metabion/controller/web/WebAuthTemplateTest.java
git commit -m "Render adaptive app dashboard"
```

---

### Task 6: Apply Shared Shell To Linked App Pages

**Files:**

- Modify: `src/main/java/com/metabion/controller/web/WebOnboardingController.java`
- Modify: `src/main/java/com/metabion/controller/web/StaffInvitationWebController.java`
- Modify: `src/main/resources/templates/onboarding.html`
- Modify: `src/main/resources/templates/onboarding-history.html`
- Modify: `src/main/resources/templates/clinical-onboarding.html`
- Modify: `src/main/resources/templates/clinical-onboarding-detail.html`
- Modify: `src/main/resources/templates/admin-staff-invitation.html`
- Modify: `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`
- Modify: `src/test/java/com/metabion/controller/web/StaffInvitationWebControllerTest.java`

- [ ] **Step 1: Add failing shared-shell assertions for onboarding pages**

In `src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java`, update existing render tests with sidebar assertions:

```java
    @Test
    void patientOnboardingPageRendersForm() throws Exception {
        mvc.perform(get("/app/onboarding")
                        .with(user("patient@example.com").roles("PATIENT")))
                .andExpect(status().isOk())
                .andExpect(view().name("onboarding"))
                .andExpect(model().attributeExists("onboardingForm"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Onboarding history")))
                .andExpect(content().string(containsString("Education library - planned")));
    }

    @Test
    void clinicalReviewListRenders() throws Exception {
        mvc.perform(get("/app/clinical/onboarding")
                        .with(user("doctor@example.com").roles("PHYSICIAN")))
                .andExpect(status().isOk())
                .andExpect(view().name("clinical-onboarding"))
                .andExpect(model().attributeExists("submissions"))
                .andExpect(content().string(containsString("class=\"sidebar\"")))
                .andExpect(content().string(containsString("Assigned patient overview - planned")));
    }
```

In `StaffInvitationWebControllerTest.admin_form_renders_with_form_and_staff_roles`, add:

```java
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("class=\"sidebar\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Content management - planned")))
```

- [ ] **Step 2: Run page tests and verify they fail**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebOnboardingControllerTest --tests com.metabion.controller.web.StaffInvitationWebControllerTest
```

Expected: FAIL because the controllers do not provide menu model attributes and templates do not use the shared shell.

- [ ] **Step 3: Inject catalog into `WebOnboardingController`**

Modify `src/main/java/com/metabion/controller/web/WebOnboardingController.java`:

```java
    private final OnboardingService onboardingService;
    private final AppMenuCatalog appMenuCatalog;

    public WebOnboardingController(OnboardingService onboardingService, AppMenuCatalog appMenuCatalog) {
        this.onboardingService = onboardingService;
        this.appMenuCatalog = appMenuCatalog;
    }
```

Add helper:

```java
    private void addAppShell(Model model, Authentication authentication, String activePath) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", activePath);
    }
```

Call it in each GET and validation-error branch that renders a template:

```java
addAppShell(model, authentication, "/app/onboarding");
```

for `/app/onboarding`;

```java
addAppShell(model, authentication, "/app/onboarding/history");
```

for history;

```java
addAppShell(model, authentication, "/app/clinical/onboarding");
```

for clinical list and detail pages.

- [ ] **Step 4: Inject catalog into `StaffInvitationWebController`**

Modify `src/main/java/com/metabion/controller/web/StaffInvitationWebController.java`:

```java
    private final StaffInvitationService staffInvitationService;
    private final AppMenuCatalog appMenuCatalog;

    public StaffInvitationWebController(StaffInvitationService staffInvitationService, AppMenuCatalog appMenuCatalog) {
        this.staffInvitationService = staffInvitationService;
        this.appMenuCatalog = appMenuCatalog;
    }
```

In `newInvitation(...)`, accept `Authentication authentication`:

```java
    public String newInvitation(Authentication authentication, Model model) {
        model.addAttribute("form", new CreateStaffInvitationRequest("", Set.of()));
        addStaffRoles(model);
        addAppShell(model, authentication);
        return "admin-staff-invitation";
    }
```

In `createInvitation(...)`, call `addAppShell(model, authentication);` before validation and before returning the form on exceptions.

Add helper:

```java
    private void addAppShell(Model model, Authentication authentication) {
        model.addAttribute("appMenuItems", appMenuCatalog.sidebarItems(authentication));
        model.addAttribute("activePath", "/app/staff-invitations/new");
    }
```

- [ ] **Step 5: Convert linked templates to shared shell**

For each linked page template, replace the opening `<html>`, `<head>`, `<body>`, `<main class="app-page">` wrapper with:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: appShell('Metabion', ${activePath}, ~{::section})}">
<section>
```

Replace the closing `</main></body></html>` with:

```html
</section>
</html>
```

Apply this to:

- `src/main/resources/templates/onboarding.html`
- `src/main/resources/templates/onboarding-history.html`
- `src/main/resources/templates/clinical-onboarding.html`
- `src/main/resources/templates/clinical-onboarding-detail.html`
- `src/main/resources/templates/admin-staff-invitation.html`

Keep each page's existing inner header, forms, tables, and links intact except for the staff invitation form action already changed in Task 3.

- [ ] **Step 6: Run linked page tests and verify they pass**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.WebOnboardingControllerTest --tests com.metabion.controller.web.StaffInvitationWebControllerTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/metabion/controller/web/WebOnboardingController.java src/main/java/com/metabion/controller/web/StaffInvitationWebController.java src/main/resources/templates/onboarding.html src/main/resources/templates/onboarding-history.html src/main/resources/templates/clinical-onboarding.html src/main/resources/templates/clinical-onboarding-detail.html src/main/resources/templates/admin-staff-invitation.html src/test/java/com/metabion/controller/web/WebOnboardingControllerTest.java src/test/java/com/metabion/controller/web/StaffInvitationWebControllerTest.java
git commit -m "Apply workbench shell to app pages"
```

---

### Task 7: Final Verification And Polish

**Files:**

- Modify only files already touched in prior tasks if verification exposes a concrete issue.

- [ ] **Step 1: Search for stale MVC staff invitation routes**

Run:

```bash
rg "/admin/staff-invitations" src/main src/test
```

Expected: only `/api/admin/staff-invitations` references remain. No MVC `/admin/staff-invitations` references should remain.

- [ ] **Step 2: Run focused web/controller tests**

Run:

```bash
./gradlew test --tests com.metabion.controller.web.AppMenuCatalogTest --tests com.metabion.controller.web.WebAuthControllerTest --tests com.metabion.controller.web.WebAuthTemplateTest --tests com.metabion.controller.web.WebOnboardingControllerTest --tests com.metabion.controller.web.StaffInvitationWebControllerTest
```

Expected: PASS.

- [ ] **Step 3: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 4: Inspect final diff**

Run:

```bash
git diff --stat HEAD
git diff HEAD -- src/main/java/com/metabion/controller/web src/main/resources/templates src/main/resources/static/css/app.css src/test/java/com/metabion/controller/web src/main/java/com/metabion/config/SecurityConfig.java
```

Expected: diff contains only the dashboard/menu/shell route changes from this plan.

- [ ] **Step 5: Commit final polish if any files changed after prior commits**

If Step 1 through Step 4 required fixes, commit the final adjustments:

```bash
git add src/main/java/com/metabion/controller/web src/main/resources/templates src/main/resources/static/css/app.css src/test/java/com/metabion/controller/web src/main/java/com/metabion/config/SecurityConfig.java src/test/java/com/metabion/config/SecurityConfigTest.java
git commit -m "Verify app dashboard shell"
```

If no files changed after Task 6, do not create an empty commit.
