# Assignment Management UX Clarity Design

Date: 2026-07-22

## Goal

Make assignment relationships and their destructive actions immediately understandable in both Assignment management views. The change is presentation-only: it must not alter assignment data, authorization, routes, confirmation behavior, or lifecycle semantics.

## Audit Evidence

The current Direct assignments and Cohorts screens both render multiple expert addresses as adjacent inline elements, causing them to run together. On the Direct assignments screen, the forms that end direct assignments are rendered after the add form, so the two identical `End assignment` buttons have no visible association with an expert.

The Cohorts screen repeats the same access-list problem on patient cards. It also gives `Save changes` and the irreversible `Archive cohort` action the same primary treatment, and presents generic repeated actions such as `Add` and `End assignment` without enough local context.

## Alternatives Considered

### 1. Minimal Separator and Button-Copy Fix

Add commas between expert emails and change the button labels. This is low effort, but still leaves destructive controls detached from the relationship they change and does not resolve the cohort view's hierarchy problem.

### 2. Unified Assignment Rows — Chosen

Render every direct, inherited, and cohort-care-team relationship as an individual row. Each row owns its metadata and, where permitted, its action. Use the same visual primitives in the Direct assignments tab and Cohorts patient/care-team cards.

This fixes scanning, action ownership, responsive reflow, and assistive-technology names without changing the workflow.

### 3. Separate Detail Pages for Every Relationship

Move assignment changes into relationship-specific pages or modals. This would make actions explicit, but adds navigation to an operational task that is currently appropriate for inline management. It is not justified for the current scale.

## Chosen Design

### Shared Assignment Row Pattern

Assignment lists use semantic lists with one row per relationship. A row contains:

- the expert email as the primary label;
- a secondary source label when the access is cohort-derived, such as `Through Keto IBD study`;
- a clearly named action on the same row when the current actor can end that relationship.

Direct rows use `End direct assignment`. Cohort care-team rows use `End care-team assignment`. Patient cohort membership uses `End cohort membership`. Existing confirmation dialogs remain, using the same relationship and actor data already supplied by the server.

On wide screens, the email and source remain on the left and the action is aligned on the right. On narrow screens, the action moves below its own row content; it never becomes separated from the expert it affects.

### Direct Assignments Tab

Each patient card has two clearly labelled groups:

- `Direct expert access` — editable rows, followed by an `Assign expert` form;
- `Access through cohorts` — read-only rows whose cohort name links to its care-team management page when the actor has permission.

The add button reads `Assign expert`, not `Add`. The cohort-management link has an accessible name that includes the cohort name, rather than the duplicated generic name `Manage care team`.

### Cohorts Tab

Patient cards use the same direct and cohort-derived row groups as the Direct assignments tab, with a contextual `Manage care team for {patient}` link. Care-team members render as individual rows with their matching end action.

The cohort list visually marks the selected cohort. The left-side create form is separated from the cohort list with its own heading and spacing so it is not mistaken for fields that edit the selected cohort.

`Add patient` and `Add staff member` replace the ambiguous `Add` labels. `Archive cohort` uses the existing danger-button treatment and is visually separated from save controls. It retains the existing confirmation dialog.

### Accessibility and Localization

Rows use structural list markup rather than adjacent inline spans. Every destructive action has visible, relationship-specific text and a contextual accessible name containing the relevant patient, expert, or cohort where needed. The selected cohort state remains exposed through a standard current/selected indicator.

All new or changed user-facing strings are added in aligned English and Czech message bundles. Danger styling supplements explicit text and confirmation; it is not the sole destructive-action signal.

## Data Flow and Error Handling

No controller, service, repository, authorization, form, or route contract changes are needed. The existing POST forms, CSRF fields, validations, conflict handling, redirects, and confirmation messages remain in place. The template changes only group their existing read-model data and forms with the relationship they operate on.

## Testing

Update the MVC template tests to assert:

- one distinct relationship row per direct, inherited, and cohort-care-team access;
- contextual end-action labels and accessible names;
- explicit assignment/add labels;
- danger styling on archive;
- the selected cohort marker and contextual manage-care-team label.

Run the focused assignment MVC tests during implementation, then verify the rendered Direct assignments and Cohorts views at desktop and narrow viewport widths. Keyboard focus, native-select validation, and confirmation behavior must continue to work.

## Scope Boundaries

This slice does not add bulk assignment, change assignment permissions, redesign the application shell, add JavaScript, alter confirmation wording, or change the persistence model.
