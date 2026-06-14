# Software Requirements for an IBD Ketogenic Nutrition Intervention Application

## Document Purpose

This document defines a draft set of functional and non-functional requirements for a web and mobile application supporting a ketogenic nutrition intervention for patients with inflammatory bowel disease, especially Crohn’s disease and ulcerative colitis. The intended first implementation is a study-ready MVP rather than a fully regulated medical-device-grade system.

The application should support patient education, eligibility screening, structured onboarding, symptom and diet tracking, laboratory value tracking, red-flag detection, expert supervision, study coordination, and structured data export for later clinical or observational analysis.

## Scope

### In Scope

- Patient-facing responsive web or PWA application.
- Optional mobile-optimized experience, with native mobile apps considered a later phase.
- Expert or coordinator web portal.
- Educational content library focused on IBD and ketogenic nutrition.
- Eligibility and safety screening.
- Patient onboarding and informed consent workflow.
- Diet, symptom, medication, weight, stool, and adverse-event tracking.
- Basic laboratory value entry and visualization.
- Red-flag detection and escalation guidance.
- Program or study management tools.
- Role-based access control.
- Audit trail for clinically and legally relevant actions.
- Export of structured pseudonymized data for research or evaluation.

### Out of Scope for the First Version

- Direct diagnosis or treatment replacement for gastroenterology care.
- Automated medical decision-making without human review.
- Native iOS and Android applications as mandatory first-release deliverables.
- Integration with national electronic health record systems.
- AI-generated clinical recommendations without expert validation.
- Full medical-device certification workflow unless the product strategy explicitly requires it.

## User Roles

### Patient

A person with IBD who wants to follow a structured ketogenic nutrition intervention, access educational materials, track progress, and receive guidance on when to contact a healthcare professional.

### Nutrition Specialist

A qualified specialist who reviews patient onboarding data, supports dietary implementation, monitors adherence and nutritional risks, and provides structured feedback.

### Physician or Gastroenterologist

A medical professional who may review safety-relevant data, inflammatory markers, red flags, medication context, and overall clinical status.

### Study Coordinator

A person responsible for participant screening, consent tracking, cohort management, protocol adherence, data completeness, and exports.

### Administrator

A technical or operational role responsible for user management, permissions, content management, configuration, and support operations.

## Product Goals

- Enable safe structured onboarding into an IBD-focused ketogenic nutrition program.
- Help patients understand the intervention, expected workflow, risks, and limits.
- Collect consistent longitudinal data on diet, symptoms, adherence, labs, and adverse events.
- Give experts a practical dashboard for monitoring patient progress and safety signals.
- Produce data suitable for feasibility assessment, pilot studies, or later research design.

## Functional Requirements

### Patient Account and Authentication

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-001 | P0 | The system shall allow patients to create an account using email and password or passwordless login. | Given a new user, when they complete registration and verify ownership of the email address, then the system creates an active patient account. |
| FR-002 | P0 | The system shall support secure login and logout. | Given an existing user, when valid credentials are provided, then the user is authenticated and redirected to the appropriate dashboard. |
| FR-003 | P0 | The system shall support password reset or account recovery. | Given a user who cannot log in, when they request account recovery, then the system sends a secure time-limited recovery link. |
| FR-004 | P1 | The system shall support optional multi-factor authentication for expert and admin roles. | Given an expert or admin user, when MFA is enabled, then login requires the second factor. |

### Role-Based Access Control

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-005 | P0 | The system shall enforce role-based access control for patients, experts, study coordinators, physicians, and administrators. | Given a user with a specific role, when they access a protected area, then only permissions assigned to that role are available. |
| FR-006 | P0 | Patients shall only access their own records unless explicit sharing is implemented. | Given Patient A and Patient B, when Patient A accesses data endpoints, then Patient B’s data is never returned. |
| FR-007 | P0 | Expert users shall only access assigned patients or cohorts. | Given an expert assigned to Cohort X, when they open the expert dashboard, then only authorized patients are visible. |

### Patient Onboarding

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-008 | P0 | The system shall collect baseline patient profile data. | Given a new patient, when onboarding starts, then the system collects age range, sex, diagnosis type, diagnosis year, disease location if known, current treatment, allergies, dietary restrictions, and key comorbidities. |
| FR-009 | P0 | The system shall collect baseline IBD status. | Given a new patient, when completing onboarding, then the system records remission or flare status, recent symptoms, stool frequency, blood in stool, abdominal pain, weight trend, and recent complications if known. |
| FR-010 | P0 | The system shall collect baseline medication information. | Given a patient on IBD therapy, when onboarding is completed, then medication type, dose if provided, and recent changes are stored. |
| FR-011 | P0 | The system shall ask about high-risk exclusion or caution criteria. | Given a new patient, when screening is completed, then the system identifies red flags such as pregnancy, severe malnutrition, active severe flare, eating disorder history, kidney disease, uncontrolled diabetes, recent hospitalization, or inability to maintain hydration. |
| FR-012 | P1 | The system shall allow upload or manual entry of recent laboratory values. | Given a patient with recent labs, when they enter values, then the system stores date, value, unit, and optional reference range. |

### Eligibility and Safety Screening

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-013 | P0 | The system shall classify onboarding status into eligible, needs expert review, or not suitable for self-guided participation. | Given a completed screening form, when rules are evaluated, then the system assigns one of the configured screening outcomes. |
| FR-014 | P0 | The system shall not present high-risk patients with a self-guided start flow. | Given a patient with high-risk answers, when screening finishes, then the system displays a recommendation to contact a healthcare professional or wait for expert review. |
| FR-015 | P0 | The system shall store screening decisions and rule triggers for auditability. | Given a screening decision, when the result is saved, then rule version, timestamp, input summary, and output category are recorded. |
| FR-016 | P1 | Experts shall be able to override or annotate screening status. | Given an assigned expert, when reviewing a patient, then they can add a documented note and update status according to permissions. |

### Informed Consent and Disclaimers

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-017 | P0 | The system shall present informed consent before any study or program participation. | Given a patient entering the program, when consent is required, then they must review and accept the current consent version before continuing. |
| FR-018 | P0 | The system shall clearly state that the application does not replace medical care. | Given a patient viewing onboarding or red-flag guidance, when medical disclaimers are shown, then the text clearly directs the patient to their treating physician for medical decisions. |
| FR-019 | P0 | The system shall record consent version, timestamp, user identity, and IP or device metadata where legally appropriate. | Given a patient signs consent, when the record is stored, then version and timestamp are immutable. |
| FR-020 | P1 | The system shall support consent withdrawal. | Given a patient who wants to withdraw, when they submit withdrawal, then future participation is stopped and data handling follows the selected consent and privacy policy. |

### Educational Content Library

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-021 | P0 | The system shall provide structured educational modules for IBD and ketogenic nutrition. | Given an onboarded patient, when they open the education section, then they can access modules about IBD basics, ketogenic diet principles, safety, hydration, electrolytes, fiber tolerance, flare considerations, and when to seek help. |
| FR-022 | P0 | Content shall be versioned and reviewed before publication. | Given an admin publishes content, when the content becomes visible, then author, reviewer, version, and publication timestamp are stored. |
| FR-023 | P1 | The system shall track completion of educational modules. | Given a patient completes a module, when progress is saved, then completion status and timestamp are visible to the patient and assigned expert. |
| FR-024 | P1 | The content library shall support Czech and English content versions. | Given a user selects a language, when content is available in that language, then the localized version is shown. |

### Nutrition Protocol Management

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-025 | P0 | The system shall define protocol phases such as preparation, adaptation, stabilization, and follow-up. | Given a patient enters the program, when the protocol starts, then the patient is assigned to a configured phase with start and expected end dates. |
| FR-026 | P0 | The system shall provide phase-specific instructions. | Given a patient is in a protocol phase, when they open the dashboard, then relevant instructions and tasks for that phase are displayed. |
| FR-027 | P1 | Experts shall be able to adjust protocol phase timing. | Given an expert reviews a patient, when they update the phase schedule, then the patient dashboard reflects the change and the action is logged. |
| FR-028 | P1 | The system shall support protocol versioning. | Given a protocol is updated, when new patients are enrolled, then they receive the current version while historical patient assignments retain prior protocol version references. |

### Diet and Adherence Tracking

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-029 | P0 | Patients shall be able to log daily dietary adherence. | Given a patient in the program, when they complete the daily check-in, then adherence level, meals, deviations, appetite, and notes are stored. |
| FR-030 | P0 | Patients shall be able to record selected food categories or meals. | Given a daily log, when the patient enters food information, then the system saves structured fields and free-text notes. |
| FR-031 | P1 | The system shall support photo-based meal logging. | Given a patient uploads a meal photo, when saved, then the image is linked to the daily log and visible to assigned experts. |
| FR-032 | P1 | The system shall support ketone or glucose entries if the protocol uses them. | Given a patient records a measurement, when value, unit, time, and context are entered, then the system stores and charts the measurement. |

### Symptom and Disease Activity Tracking

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-033 | P0 | Patients shall complete a regular symptom check-in. | Given a configured schedule, when the patient opens the app, then they can record stool frequency, stool consistency, blood, urgency, abdominal pain, fatigue, fever, nausea, appetite, and general wellbeing. |
| FR-034 | P0 | The system shall calculate simple symptom trends. | Given at least two symptom entries, when the patient or expert opens trends, then changes over time are displayed. |
| FR-035 | P1 | The system shall support standardized questionnaires if selected by the study team. | Given a questionnaire is enabled, when due, then the patient completes the configured form and the result is stored. |
| FR-036 | P1 | Patients shall be able to mark flare or suspected flare events. | Given a patient experiences worsening symptoms, when they mark a flare event, then the event appears in their timeline and expert dashboard. |

### Laboratory and Biomarker Tracking

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-037 | P0 | The system shall support manual entry of relevant laboratory values. | Given a lab entry form, when the user enters CRP, fecal calprotectin, hemoglobin, ferritin, vitamin D, B12, albumin, electrolytes, liver enzymes, kidney markers, or other configured values, then the data is stored with date and unit. |
| FR-038 | P0 | The system shall visualize lab trends over time. | Given multiple lab values of the same type, when the trend view opens, then the chart shows values by date. |
| FR-039 | P1 | The system shall support file upload for lab reports. | Given a patient uploads a PDF or image, when upload succeeds, then the file is stored securely and linked to the patient record. |
| FR-040 | P2 | The system may support structured extraction from lab reports. | Given a lab report is uploaded, when extraction is run, then parsed values are shown for human confirmation before saving. |

### Red-Flag Detection and Escalation

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-041 | P0 | The system shall detect configured red-flag patterns. | Given symptom, weight, hydration, fever, bleeding, pain, or lab inputs, when thresholds are met, then the system creates a red-flag event. |
| FR-042 | P0 | The system shall provide immediate safety guidance for red flags. | Given a red-flag event, when the patient sees the result, then the app advises appropriate action such as contacting their physician, urgent care, or emergency services depending on severity. |
| FR-043 | P0 | Experts shall be notified of red-flag events for assigned patients. | Given a red flag occurs, when the event is created, then assigned expert users receive an in-app alert or configured notification. |
| FR-044 | P0 | Red-flag rules shall be versioned and auditable. | Given a rule triggers, when the event is stored, then rule ID, rule version, input data, severity, and timestamp are logged. |

### Patient Dashboard

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-045 | P0 | Patients shall see a daily dashboard with tasks and status. | Given a patient logs in, when the dashboard loads, then it displays today’s check-ins, protocol phase, education tasks, alerts, and recent trends. |
| FR-046 | P0 | Patients shall be able to view their timeline. | Given a patient has logs, labs, notes, and events, when they open the timeline, then entries are shown chronologically. |
| FR-047 | P1 | Patients shall receive reminders for missing logs or scheduled tasks. | Given a reminder schedule, when a task is due or overdue, then the patient receives a configured reminder. |

### Expert and Coordinator Dashboard

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-048 | P0 | Experts shall see an overview of assigned patients. | Given an expert logs in, when the dashboard opens, then patients are listed with adherence, symptom trend, red flags, missing data, and current phase. |
| FR-049 | P0 | Experts shall be able to open a patient detail view. | Given an authorized expert selects a patient, when the detail page opens, then profile, screening, consent, logs, labs, notes, events, and timeline are visible. |
| FR-050 | P0 | Coordinators shall manage participant status. | Given a coordinator reviews participants, when they update screening, consent, active, paused, completed, or withdrawn status, then the system stores the change. |
| FR-051 | P1 | Experts shall be able to add structured notes. | Given an expert reviews a patient, when they add a note, then the note is timestamped, attributed, and included in audit history. |
| FR-052 | P1 | The dashboard shall include filters for cohort, status, risk level, missing data, and protocol phase. | Given many patients, when filters are applied, then only matching patients are displayed. |

### Communication and Support

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-053 | P1 | The system shall support secure patient-to-expert messaging or structured questions. | Given messaging is enabled, when a patient sends a message, then assigned experts can view and respond within the system. |
| FR-054 | P1 | The system shall distinguish medical urgency from normal support questions. | Given a patient tries to send a message containing severe symptoms, when red-flag terms or questionnaire answers are detected, then the system displays urgent guidance instead of treating it as routine support only. |
| FR-055 | P2 | The system may support asynchronous group education sessions or announcements. | Given an admin posts an announcement, when patients in the target cohort open the app, then the announcement is visible. |

### Study Management

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-056 | P0 | The system shall support cohort creation and participant assignment. | Given a coordinator creates a cohort, when patients are assigned, then cohort membership is reflected in dashboards and exports. |
| FR-057 | P0 | The system shall track participant lifecycle status. | Given a participant changes state, when the status is updated, then the system records screened, consented, enrolled, active, paused, completed, excluded, or withdrawn. |
| FR-058 | P0 | The system shall track data completeness. | Given required fields are configured, when a coordinator opens the study dashboard, then missing or overdue data is highlighted. |
| FR-059 | P1 | The system shall support visit or checkpoint schedules. | Given a study schedule is configured, when dates are reached, then tasks and reminders are generated. |

### Data Export and Reporting

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-060 | P0 | The system shall export pseudonymized structured data for analysis. | Given an authorized coordinator requests export, when parameters are selected, then the system exports CSV or XLSX datasets without directly identifying personal data unless explicitly authorized. |
| FR-061 | P0 | The system shall maintain a data dictionary for exported fields. | Given an export is generated, when the data dictionary is downloaded, then each field has a name, description, type, allowed values, and unit where relevant. |
| FR-062 | P0 | Export access shall be restricted and audited. | Given a user exports data, when the export completes, then user identity, timestamp, cohort, and export type are logged. |
| FR-063 | P1 | The system shall support basic aggregate reports. | Given a coordinator opens reports, when cohort data is available, then the system shows adherence, completion, symptom trends, missing data, and red-flag counts. |

### Content Management

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-064 | P1 | Admins shall manage educational content. | Given an admin has permission, when they create or edit content, then drafts can be reviewed and published. |
| FR-065 | P1 | Admins shall manage configurable screening and red-flag rules through a controlled process. | Given rule changes are proposed, when approved and published, then a new rule version becomes active. |
| FR-066 | P2 | The system may support A/B testing of non-clinical educational content. | Given experiments are enabled, when content variants are assigned, then only non-safety-critical content can be tested. |

### Notifications

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| FR-067 | P1 | The system shall send reminders for daily logs and scheduled checkpoints. | Given a patient has pending tasks, when reminder time arrives, then the system sends an email, push, or in-app notification according to settings. |
| FR-068 | P1 | Patients shall be able to configure notification preferences. | Given a patient opens settings, when preferences are updated, then future notifications follow those settings unless legally or safety-required. |
| FR-069 | P0 | Critical safety notifications shall not be silently disabled if the user is actively participating in monitored mode. | Given safety alerts are required by program policy, when a user changes preferences, then the system explains which alerts remain mandatory. |

## Non-Functional Requirements

### Security

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-001 | P0 | All communication shall use TLS. | All production traffic is served over HTTPS with modern TLS configuration. |
| NFR-002 | P0 | Sensitive data shall be encrypted at rest. | Database, backups, and file storage use encryption at rest. |
| NFR-003 | P0 | Passwords shall be hashed using a modern password-hashing algorithm. | No plaintext passwords are stored; password hashes use a current secure algorithm and parameters. |
| NFR-004 | P0 | The system shall protect against common web vulnerabilities. | Security testing covers OWASP Top 10 risks, including injection, XSS, CSRF where relevant, access-control flaws, and insecure deserialization. |
| NFR-005 | P0 | Production secrets shall not be stored in source code. | Secrets are managed through secure configuration or secret-management tooling. |

### Privacy and GDPR

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-006 | P0 | The system shall treat health data as sensitive personal data. | Data inventory classifies IBD status, symptoms, labs, medications, diet logs, and uploaded reports as sensitive health-related data. |
| NFR-007 | P0 | The system shall support GDPR data-subject rights workflows. | Authorized admins can process access, rectification, export, restriction, and erasure requests according to policy and legal basis. |
| NFR-008 | P0 | The system shall implement data minimization. | Each collected data field has a documented purpose and owner. |
| NFR-009 | P0 | The system shall support retention and deletion policies. | Data retention rules are configurable or documented, and deletion or anonymization workflows are available. |
| NFR-010 | P0 | The system shall support pseudonymization for research exports. | Exported research datasets use participant IDs rather than direct identifiers by default. |

### Auditability

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-011 | P0 | The system shall maintain audit logs for sensitive actions. | Login, consent, screening decisions, red flags, role changes, exports, expert notes, and status changes are logged. |
| NFR-012 | P0 | Audit logs shall be tamper-resistant. | Normal application users cannot edit or delete audit events through application interfaces. |
| NFR-013 | P1 | Audit logs shall support filtering and export by authorized admins. | Given an authorized admin, when they query audit logs, then results can be filtered by user, patient, event type, and date. |

### Reliability and Availability

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-014 | P0 | The production system shall have automated backups. | Backups run on a documented schedule and restore is tested periodically. |
| NFR-015 | P0 | The system shall handle graceful failure for non-critical services. | If notifications or exports fail, core login and data entry remain available where possible. |
| NFR-016 | P1 | The system shall target high availability appropriate for a study-ready MVP. | Uptime objectives are documented and monitored; incidents are tracked. |

### Performance

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-017 | P1 | Common patient screens shall load within acceptable time under expected load. | Dashboard, daily log, and education pages meet agreed performance thresholds in staging tests. |
| NFR-018 | P1 | Expert dashboard filtering shall remain usable for the expected cohort size. | With expected pilot-scale data, dashboard filters and patient detail pages respond within agreed limits. |
| NFR-019 | P2 | The system architecture shall allow scaling to multiple cohorts. | Data model and infrastructure do not assume a single study or single cohort. |

### Usability and Accessibility

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-020 | P0 | Patient flows shall be understandable to non-technical users. | Usability testing confirms that patients can complete onboarding, daily logs, and education tasks without expert assistance. |
| NFR-021 | P1 | The application shall follow accessibility best practices. | UI design supports keyboard navigation, sufficient contrast, semantic structure, and screen-reader compatibility for core flows. |
| NFR-022 | P1 | Mobile web usage shall be first-class. | Core patient flows are usable on common mobile screen sizes. |

### Maintainability

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-023 | P0 | The codebase shall separate patient, expert, admin, and backend concerns clearly. | Architecture documentation identifies modules, responsibilities, and integration points. |
| NFR-024 | P0 | The system shall include automated tests for critical flows. | CI runs tests for authentication, authorization, consent, screening, red flags, data entry, and export. |
| NFR-025 | P1 | Clinical or safety rules shall be configurable and versioned. | Rule changes do not require uncontrolled code edits without review and version tracking. |
| NFR-026 | P1 | API contracts shall be documented. | Backend endpoints are documented using OpenAPI or equivalent. |

### Observability and Operations

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-027 | P0 | The system shall collect application logs and error reports. | Production errors are captured with sufficient context while avoiding sensitive data leakage. |
| NFR-028 | P1 | The system shall include basic monitoring and alerting. | Availability, error rate, background jobs, and notification failures are monitored. |
| NFR-029 | P1 | The system shall support environment separation. | Development, staging, and production environments are isolated. |

### Localization

| ID | Priority | Requirement | Acceptance Criteria |
|---|---:|---|---|
| NFR-030 | P1 | The system shall support Czech and English UI text. | User-facing strings are externalized and can be localized. |
| NFR-031 | P1 | Medical and educational content shall be localized through a review process. | Localized content versions have reviewer, version, and publication metadata. |

## Suggested MVP Phasing

### Phase 1: Lean Pre-Pilot

- Patient registration and login.
- Basic onboarding and eligibility screening.
- Consent and disclaimers.
- Educational content library.
- Daily symptom and diet log.
- Basic expert dashboard.
- Red-flag detection.
- Manual lab entry.
- Pseudonymized CSV export.

### Phase 2: Study-Ready MVP

- Cohort and participant management.
- Protocol phases and scheduled checkpoints.
- Data completeness monitoring.
- More robust audit trail.
- Expert notes.
- Configurable questionnaires.
- Notification system.
- Data dictionary and structured exports.
- Improved mobile PWA experience.

### Phase 3: Expanded Product

- Native mobile applications if justified by usage.
- Meal photo logging.
- Lab report uploads and optional structured extraction.
- Advanced analytics.
- More integrations.
- More formal quality-management and regulatory documentation if the product strategy requires it.

## Key Open Questions

- What exact clinical or nutritional protocol will be used for each patient segment?
- Will the first release be positioned as education and self-management support, a supervised nutrition program, or a clinical study tool?
- Who will be the legal data controller and who will act as processor or sub-processor?
- Which patient groups should be excluded from self-guided participation?
- Which red flags require immediate emergency guidance versus expert review?
- Which standardized disease activity or quality-of-life questionnaires should be included?
- Which laboratory values are mandatory, optional, or expert-only?
- Will the system handle minors, or only adults?
- Will messaging be included in the first version, and who is responsible for response times?
- Is the target first deployment Czech-only, English-only, or bilingual from day one?

## Implementation Recommendation

For the first serious release, the recommended approach is a responsive web application or PWA for patients plus a web-based expert and coordinator portal. This avoids the cost and maintenance burden of native iOS and Android applications while still supporting the core study-ready workflows.

Native mobile applications, AI-assisted coaching, advanced integrations, and medical-device-grade quality management should be treated as later phases unless they are essential to the initial regulatory or commercial strategy.
