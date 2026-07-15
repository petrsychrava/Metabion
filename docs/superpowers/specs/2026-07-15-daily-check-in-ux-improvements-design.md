# Daily Check-In UX Improvements Design

## Goal

Make the patient daily check-in faster to scan and complete, safer against accidental data loss, and more accessible while preserving its existing single-form workflow and clinical data contracts.

## Scope

The change covers the server-rendered daily check-in at `/app/daily-check-in` and its `/app/diet-logs` alias. It improves page layout, section disclosure, completion feedback, dynamic meal and photo behavior, unsaved-change protection, validation feedback, and accessibility.

Food category is already removed from the application and is not part of this change. Patient-facing red-flag guidance is deferred until clinician-approved thresholds and wording exist. Copying meals from previous days is also deferred. The change does not alter database schema, persisted domain models, REST APIs, symptom scoring, clinical trends, authentication, or authorization.

## Experience Structure

The check-in remains one form containing four independently collapsible cards:

- Diet is required and expanded initially.
- Measurements is optional and collapsed initially.
- Meals is optional and collapsed initially.
- Symptoms is required and collapsed initially.

Each section header identifies the section as required or optional and displays a state such as `Not started`, `In progress`, or `Complete`. Opening one section does not close the others, so patients can compare and revise information across sections. The controls use native disclosure semantics and remain understandable when JavaScript is unavailable.

Section states follow the existing submission contract:

- Diet is `Not started` while adherence and appetite are blank, `In progress` when only one is selected, and `Complete` when both are selected and the date is present.
- Measurements is `Not started` when both cards are blank, `In progress` while a started measurement lacks value, time, or context, and `Complete` when every started measurement has those fields.
- Meals is `Not started` when all reusable rows are blank, `In progress` while a populated meal lacks meal type or a selected deviation lacks severity, and `Complete` when all populated meals and deviations satisfy those existing requirements.
- Symptoms is `Not started` when flare state and answers are blank, `In progress` after any symptom input, and `Complete` when flare state and every required questionnaire answer are present.

Server validation errors override these labels with a localized `Needs attention` state for the affected section. The sticky action area reports completion of the two required sections; optional sections do not prevent the action from showing required work as complete.

The date and history link remain in the page header area. A sticky action area keeps the save action and overall completion status visible without covering content, including at mobile widths. Existing saved check-ins reopen with their populated sections and corresponding completion states.

## Field and Disclosure Behavior

Diet continues to show date, adherence, appetite, and general notes. Date, adherence, and appetite are visibly marked required; notes are visibly marked optional.

Measurements continues to show separate glucose and ketone cards with the patient's configured glucose unit and the fixed ketone unit. The section is optional, and existing measurement validation and persistence behavior remain unchanged.

Meals starts with one reusable blank meal row instead of two. Patients can add and remove meal rows dynamically. The last remaining row is cleared instead of removed so the section always retains an entry point. Meal type, food description, and meal notes remain visible when a meal is open. Deviation severity and notes remain hidden until a deviation category is selected. Photo captions appear only after a photo has uploaded successfully. Existing meal, deviation, photo, and ordering contracts remain unchanged.

Symptoms no longer defaults the flare state to `No flare`; the patient must make an explicit selection. Questionnaire fields visibly identify whether they are required. Questionnaire versioning, option values, scoring, and persistence remain unchanged.

## Data-Loss Prevention and Feedback

The browser tracks whether the form differs from its loaded or most recently saved state. Changing the date on a clean form loads that date immediately. Changing the date after an edit asks for confirmation; cancelling restores the currently loaded date. Navigating away from a dirty form invokes the standard browser unsaved-change warning. Successful submission clears the dirty state before navigation.

After a successful save, the redirected page displays a localized status message containing the saved date. Validation failures display a linked error summary, expand the first section containing an error, and move focus to the first invalid field. Existing field-level validation remains available.

## Accessibility

Repeated controls receive contextual accessible names that include their subject, such as `Glucose value`, `Meal 2 notes`, and `Remove meal 2`. The visible labels may remain concise where the accessible name supplies the missing context.

Adding or removing a meal moves focus to a predictable nearby control and announces the result through an ARIA live region. Successful and failed photo uploads are both announced and leave visible status text. Section status changes and successful saves use appropriate status semantics without interrupting normal typing.

Keyboard users can operate disclosures, dynamic meal controls, photo uploads, validation links, and the sticky save action. Focus is never left on a removed element. Server-side submission and validation remain functional without JavaScript.

## Technical Design

`WebDailyCheckInController` will create one initial meal row, stop assigning a default flare state for a new symptom check-in, and use redirect flash attributes for the localized post-save confirmation. Its existing form-to-service mapping and error handling remain the server-side source of truth.

`daily-check-in.html` will provide the disclosure structure, section status elements, explicit required and optional text, contextual accessibility metadata, error summary, and live regions. The page will load a dedicated `daily-check-in.js` script for progressive behavior, completion-state updates, dirty-state protection, dynamic meal and photo interactions, and focus management.

Shared diet-log layout rules currently embedded in the obsolete `diet-logs.html` template will be implemented in `app.css` with selectors scoped under the active daily-check-in form. This restores the intended card and grid layout without changing unrelated templates that reuse individual class names. Daily-check-in-specific responsive and sticky-action styles will use the same scope.

The obsolete `diet-logs.html` template is not deleted or refactored. Both active patient form routes continue to render `daily-check-in.html`.

All new user-facing text will be added to both `messages.properties` and `messages_cs.properties` with aligned keys.

## Error Handling

Client-side enhancement failures must not block ordinary server submission. Photo upload failures remain within the affected meal, expose a visible localized message, and do not create a photo reference. Server validation errors retain the submitted form values and open the affected disclosure section. The existing controller handling of bad-request responses from `DailyCheckInService` remains intact and participates in the page-level error presentation.

## Validation and Testing

Focused controller and rendered-template tests will cover:

- one initial blank meal row;
- no preselected flare state for a new check-in;
- preservation of an existing saved flare state;
- successful redirect and dated flash confirmation;
- validation-error rendering and submitted-value preservation;
- required and optional indicators, disclosure semantics, live regions, and contextual accessible names;
- continued support for the `/app/diet-logs` alias.

Existing daily-check-in service and persistence tests will verify that the UI-only changes do not alter application contracts. The full `./gradlew test` suite will provide regression coverage.

Manual Chrome verification will cover desktop and mobile widths, light and dark themes, keyboard-only operation, date changes on clean and dirty forms, adding and removing meals, deviation disclosure, successful and failed photo uploads, save feedback, validation focus, and submission with JavaScript disabled.

## Constraints

- Do not reintroduce food category anywhere.
- Do not implement or imply clinical red-flag thresholds or advice.
- Do not add copy-previous-meal behavior.
- Do not introduce a wizard or separate persistence per section.
- Do not add a frontend build system or test dependency for this change.
- Keep all new text localized in English and Czech.
- Preserve unrelated dirty files in the worktree.
