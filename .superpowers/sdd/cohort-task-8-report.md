# Task 8: Localized Cohort-Centric Workspace

## Summary

Implemented the cohort-centric assignment-management Thymeleaf workspace with:

- Cohort list/detail and direct-assignment views in the shared application shell.
- Active/archived state, archived-detail read-only controls, and admin-only archive action.
- Cohort patient/staff assignment controls plus direct/inherited access summaries.
- Per-patient direct-assignment staff candidate selects (`patient.staffCandidates()`).
- CSRF hidden fields and localized, name-specific confirmation prompts for end/archive actions.
- English and Czech assignment copy, retaining the existing assignment-management menu keys.
- Responsive one-column assignment layout at 800px and below.

## Files

- `src/main/resources/templates/assignment-management.html` (new)
- `src/main/resources/messages.properties`
- `src/main/resources/messages_cs.properties`
- `src/main/resources/static/css/app.css`
- `src/test/java/com/metabion/controller/web/AssignmentManagementWebControllerTest.java`

## TDD evidence

- RED: `./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest'` failed after adding rendered-template assertions because `assignment-management.html` was absent (`TemplateInputException` / `FileNotFoundException`).
- GREEN: the same focused controller test passed after adding the template, localization resources, and styles.
- Focused regression: `./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest' --tests 'com.metabion.controller.web.AppMenuCatalogTest'` passed.

## Full-suite verification

- `./gradlew test --console=plain` completed without reported test failures.

## Self-review

- Confirmed all `assignment.*` keys align across English and Czech bundles; existing `menu.assignmentManagement*` keys were not duplicated.
- Confirmed direct controls use `patient.staffCandidates()` and contain no stale `directPage.staffCandidates()` binding.
- Confirmed all workspace mutation forms include the real CSRF parameter/token; destructive forms use `data-confirm` and `onsubmit` confirmation.
- Confirmed archived selected-cohort controls are not rendered and coordinator targets are supplied only by the scoped service candidates.

## Concerns

None. The Gradle runtime emits pre-existing JVM restricted-method warnings, but no test failures.

## Review fixes

### Separate create and edit bindings

- The create and edit handlers now bind `createCohortForm` and `editCohortForm` independently.
- Cohort rendering initializes an empty create form and initializes the edit form from the selected cohort only when no bound edit form is already present.
- The template now renders form-level review feedback and field-level name/description errors for each form. Invalid edit submissions remain in the edit form and do not populate the create form.

### Localized tab state

- Added aligned `assignment.views` messages: `Assignment views` / `Pohledy přiřazení`.
- The tab navigation uses that localized label; exactly the active cohort or direct tab receives `aria-current="page"` and the corresponding visual style.

### TDD evidence

- RED: `./gradlew test --tests 'com.metabion.controller.web.AssignmentManagementWebControllerTest' --tests 'com.metabion.controller.web.AppMenuCatalogTest' --rerun-tasks` ran 26 tests with 3 expected failures: independent create/edit rendered validation and localized current-tab assertions.
- GREEN: the same command passed after the binding and tab-state fixes. The final rerun also passed with 26 tests and no failures.

### Self-review

- Verified no `cohortForm` binding remains in the assignment controller/template tests.
- Verified English and Czech `assignment.*` key sets remain aligned.
- Verified unchanged routes, service calls, CSRF markup, archive/role controls, and scoped candidate bindings.
