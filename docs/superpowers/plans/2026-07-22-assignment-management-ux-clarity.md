# Assignment Management UX Clarity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make direct, cohort-derived, and cohort-care-team assignments readable and bind every destructive action to the relationship it changes.

**Architecture:** Preserve the current MVC routes, forms, services, confirmation dialogs, and access decisions. Restructure only the assignment-management Thymeleaf markup into semantic relationship rows, add localized contextual labels, and use scoped responsive CSS.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Thymeleaf, Spring Security CSRF, JUnit 5, MockMvc, Hamcrest, CSS.

## Global Constraints

- Do not change controllers, service methods, repositories, domain entities, migrations, routes, CSRF fields, confirmation messages, or authorization rules.
- Preserve direct-assignment pagination and its `page` hidden input.
- Keep all new and changed copy aligned between English and Czech message bundles.
- Use relationship-specific visible action labels; color and confirmation dialogs are supplementary.
- Do not add JavaScript, dependencies, bulk assignment, modals, or new pages.
- Preserve unrelated dirty worktree files.

## File Structure

- Modify `src/main/resources/templates/assignment-management.html`: semantic relationship groups and rows; contextual controls and accessible labels; selected cohort state; clear creation/form headings.
- Modify `src/main/resources/static/css/app.css`: assignment-row layout, selected cohort marker, danger action treatment, and responsive reflow.
- Modify `src/main/resources/messages.properties`: English assignment-management labels.
- Modify `src/main/resources/messages_cs.properties`: matching Czech labels.
- Modify `src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java`: rendered-markup and localization regression coverage.

---

### Task 1: Render Contextual, Semantic Assignment Rows

**Files:**
- Modify: `src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java:74-173,568-600`
- Modify: `src/main/resources/templates/assignment-management.html:20-250`
- Modify: `src/main/resources/messages.properties:556-605`
- Modify: `src/main/resources/messages_cs.properties:556-605`

**Interfaces:**
- Consumes: existing `CohortPage`, `PatientRow`, `DirectPage`, `DirectPatient`, and `ExpertAccess` view records.
- Produces: `assignment-access-list`, `assignment-access-row`, `assignment-row-action`, and contextual labels in the rendered page; no Java interface changes.

- [ ] **Step 1: Write failing rendered-markup tests**

Add these tests after `directWorkspaceUsesEachPatientsStaffCandidatesAndLinksInheritedAccessToCohorts()`:

```java
@Test
void directWorkspaceBindsEachEndActionToItsDirectExpert() throws Exception {
    stubAppShell();
    var authentication = auth("admin@example.com", RoleName.ADMIN);
    when(assignments.directPage(same(authentication))).thenReturn(directPage());

    mvc.perform(get("/app/assignment-management/direct").principal(authentication))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "class=\"assignment-access-list\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "End direct assignment")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "Assign expert")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "aria-label=\"Manage cohort “Pilot cohort”\"")))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                    .containsPattern("(?s)doctor@example\\.com.*?End direct assignment"));
}

@Test
void cohortWorkspaceUsesSpecificActionsAndMarksTheSelectedCohort() throws Exception {
    stubAppShell();
    var authentication = auth("admin@example.com", RoleName.ADMIN);
    when(assignments.cohortPage(same(authentication), eq(10L))).thenReturn(activeCohortPage(true));

    mvc.perform(get("/app/assignment-management/cohorts/10").principal(authentication))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "aria-current=\"page\">Pilot cohort")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "Create new cohort")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "Add patient to cohort")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "Add staff member to cohort")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "End cohort membership")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "End care-team assignment")));
}
```

Extend `assignmentWorkspaceUsesCzechCopyWhenLocaleCookieIsCzech()` with:

```java
.andExpect(content().string(org.hamcrest.Matchers.containsString("Přiřadit odborníka")))
.andExpect(content().string(org.hamcrest.Matchers.containsString("Ukončit přímé přiřazení")));
```

- [ ] **Step 2: Run the focused tests and verify the intended failure**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest.directWorkspaceBindsEachEndActionToItsDirectExpert' --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest.cohortWorkspaceUsesSpecificActionsAndMarksTheSelectedCohort' --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest.assignmentWorkspaceUsesCzechCopyWhenLocaleCookieIsCzech'
```

Expected: FAIL because the template has inline access spans, generic actions, no selected-cohort marker, and no new localized keys.

- [ ] **Step 3: Add aligned English and Czech copy**

Add these keys adjacent to the existing assignment labels in `messages.properties`:

```properties
assignment.createNewCohort=Create new cohort
assignment.directExpertAccess=Direct expert access
assignment.assignExpert=Assign expert
assignment.addPatientToCohort=Add patient to cohort
assignment.addStaffToCohort=Add staff member to cohort
assignment.endCohortMembership=End cohort membership
assignment.endDirectAssignment=End direct assignment
assignment.endCareTeamAssignment=End care-team assignment
assignment.manageCareTeamForPatient=Manage care team for {0}
assignment.manageCohort=Manage cohort “{0}”
```

Add the same keys in `messages_cs.properties` with these exact values:

```properties
assignment.createNewCohort=Vytvořit novou kohortu
assignment.directExpertAccess=Přímý přístup odborníků
assignment.assignExpert=Přiřadit odborníka
assignment.addPatientToCohort=Přidat pacienta do kohorty
assignment.addStaffToCohort=Přidat člena personálu do kohorty
assignment.endCohortMembership=Ukončit členství v kohortě
assignment.endDirectAssignment=Ukončit přímé přiřazení
assignment.endCareTeamAssignment=Ukončit přiřazení pečujícího týmu
assignment.manageCareTeamForPatient=Spravovat pečující tým pacienta {0}
assignment.manageCohort=Spravovat kohortu „{0}”
```

- [ ] **Step 4: Replace inline access spans with relationship rows**

In `assignment-management.html`, replace every direct/inherited `th:each` span or block with an `ul class="assignment-access-list"` and one `li class="assignment-access-row"` per `ExpertAccess`.

Use this direct-access group in the Direct assignments patient card. It keeps the existing end endpoint, CSRF token, confirmation, patient id, assignment id, and pagination form unchanged:

```html
<section class="assignment-access-group">
    <h4 th:text="#{assignment.directExpertAccess}">Direct expert access</h4>
    <ul class="assignment-access-list">
        <li th:each="access : ${patient.direct()}" class="assignment-access-row">
            <span th:text="${access.email()}">expert@example.com</span>
            <form class="assignment-row-action"
                  th:action="@{/app/assignment-management/patients/{patientId}/direct-assignments/{assignmentId}/end(
                      patientId=${patient.patientProfileId()}, assignmentId=${access.assignmentId()})}"
                  method="post" onsubmit="return confirm(this.dataset.confirm)"
                  th:attr="data-confirm=#{assignment.confirm.endDirect(${access.email()}, ${patient.email()})}">
                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
                <button type="submit" th:text="#{assignment.endDirectAssignment}">End direct assignment</button>
            </form>
        </li>
    </ul>
</section>
```

Keep the existing direct-assignment form after this group, but change its submit key to `assignment.assignExpert`.

Use this inherited row in the Direct assignments patient card; preserve the existing `cohortManageable()` gate:

```html
<li th:each="access : ${patient.inherited()}" class="assignment-access-row">
    <a th:if="${access.cohortManageable()}"
       th:href="@{/app/assignment-management/cohorts/{id}(id=${access.cohortId()})}"
       th:attr="aria-label=#{assignment.manageCohort(${access.cohortName()})}"
       th:text="${access.email() + ' · ' + access.cohortName()}">Expert · Cohort</a>
    <span th:if="${!access.cohortManageable()}"
          th:text="${access.email() + ' · ' + access.cohortName()}">Expert · Cohort</span>
</li>
```

Apply the same group/list/row structure to cohort patient cards. Keep their patient-level management link, but set `aria-label=#{assignment.manageCareTeamForPatient(${patient.email()})}`. Place each care-team email and its existing end form in one `assignment-access-row`, and use `assignment.endCareTeamAssignment`; use `assignment.endCohortMembership` for the membership form.

- [ ] **Step 5: Clarify selection and generic controls**

On every cohort link, add:

```html
th:attr="aria-current=${cohortPage.selected() != null and cohortPage.selected().id() == cohort.id()} ? 'page' : null"
```

Insert this heading immediately before the create-cohort form:

```html
<h3 th:text="#{assignment.createNewCohort}">Create new cohort</h3>
```

Change the patient and care-team submit buttons to `assignment.addPatientToCohort` and `assignment.addStaffToCohort`.

- [ ] **Step 6: Run the focused rendering tests and verify they pass**

Run the Step 2 command again.

Expected: PASS. Existing delegation, conflict, validation, CSRF, pagination, and confirmation tests keep passing because the form contracts are unchanged.

- [ ] **Step 7: Commit the markup, copy, and regression tests**

```bash
git add src/main/resources/templates/assignment-management.html src/main/resources/messages.properties src/main/resources/messages_cs.properties src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java
git commit -m "Clarify assignment relationship actions"
```

### Task 2: Style Rows, Selection, and Destructive Actions

**Files:**
- Modify: `src/main/resources/static/css/app.css:632-684`

**Interfaces:**
- Consumes: the `assignment-access-group`, `assignment-access-list`, `assignment-access-row`, `assignment-row-action`, and `button-danger` classes from Task 1.
- Produces: a responsive layout where every action stays with its relationship and archive is visually distinct from save.

- [ ] **Step 1: Add scoped assignment-management CSS**

Append the following rules after the existing assignment patient/staff rules:

```css
.assignment-cohorts a[aria-current="page"] {
    background: var(--secondary-bg);
    box-shadow: inset 3px 0 0 var(--accent);
    font-weight: 700;
}

.assignment-cohorts .form {
    border-top: 1px solid var(--border);
    padding-top: 1rem;
}

.assignment-access-group {
    display: grid;
    gap: 0.5rem;
}

.assignment-access-group h4 {
    margin: 0;
    font-size: 0.95rem;
}

.assignment-access-list {
    display: grid;
    gap: 0.5rem;
    list-style: none;
    margin: 0;
    padding: 0;
}

.assignment-access-row {
    align-items: center;
    border: 1px solid var(--border);
    border-radius: 0.5rem;
    display: flex;
    gap: 0.75rem;
    justify-content: space-between;
    padding: 0.625rem 0.75rem;
}

.assignment-access-row > :first-child {
    min-width: 0;
    overflow-wrap: anywhere;
}

.assignment-row-action {
    margin: 0;
}

.assignment-row-action button {
    white-space: nowrap;
}

.button-danger {
    background: var(--error);
}

.button-danger:hover {
    filter: brightness(0.9);
}
```

Inside the existing `@media (max-width: 800px)` block, add:

```css
.assignment-access-row {
    align-items: stretch;
    flex-direction: column;
}

.assignment-row-action,
.assignment-row-action button {
    width: 100%;
}
```

- [ ] **Step 2: Run the assignment MVC test class**

Run:

```bash
./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest'
```

Expected: PASS; the final template remains valid under the localized MVC test harness.

- [ ] **Step 3: Visually verify both tabs at desktop and narrow width**

Using the authenticated local application, inspect `/app/assignment-management/direct` and `/app/assignment-management/cohorts/10` at the normal desktop viewport and at 800 px or narrower. Confirm:

- each expert has its own row and no emails concatenate;
- every end button appears in the row for the relationship it ends;
- direct, cohort-derived, and care-team access are distinguishable;
- the selected cohort is marked;
- Archive cohort is danger-styled and distinct from Save changes;
- actions stack without overflow at narrow width;
- tab navigation, native select validation, confirmation prompts, and keyboard focus still work.

- [ ] **Step 4: Commit the scoped visual treatment**

```bash
git add src/main/resources/static/css/app.css
git commit -m "Improve assignment management action hierarchy"
```

### Task 3: Full Regression Verification

**Files:**
- No source changes.

**Interfaces:**
- Consumes: Tasks 1 and 2 with all form and route contracts unchanged.
- Produces: final verification evidence.

- [ ] **Step 1: Run the full automated suite**

Run:

```bash
./gradlew test
```

Expected: PASS with JaCoCo output under `build/reports/jacoco/test/html/`.

- [ ] **Step 2: Inspect final scope**

Run:

```bash
git status --short
git log -2 --oneline
```

Expected: only the two focused UX commits are new; pre-existing `.idea/`, `.superpowers/`, plan, and `var/` changes remain unstaged and untouched.
