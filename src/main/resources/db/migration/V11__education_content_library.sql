CREATE TABLE education_modules (
    id                              BIGSERIAL PRIMARY KEY,
    slug                            VARCHAR(120) NOT NULL UNIQUE,
    topic                           VARCHAR(80) NOT NULL,
    sort_order                      INT NOT NULL,
    current_published_version_id    BIGINT,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_education_modules_slug_not_blank CHECK (length(trim(slug)) > 0),
    CONSTRAINT chk_education_modules_slug_format CHECK (slug = lower(slug) AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT chk_education_modules_sort_positive CHECK (sort_order > 0)
);

CREATE TABLE education_module_versions (
    id                      BIGSERIAL PRIMARY KEY,
    module_id               BIGINT NOT NULL REFERENCES education_modules(id) ON DELETE CASCADE,
    version                 INT NOT NULL,
    status                  VARCHAR(40) NOT NULL,
    author_user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_by_user_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    published_by_user_id    BIGINT REFERENCES users(id) ON DELETE SET NULL,
    review_bypassed         BOOLEAN NOT NULL DEFAULT FALSE,
    review_notes            VARCHAR(2000),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    submitted_at            TIMESTAMP WITH TIME ZONE,
    reviewed_at             TIMESTAMP WITH TIME ZONE,
    published_at            TIMESTAMP WITH TIME ZONE,
    archived_at             TIMESTAMP WITH TIME ZONE,

    CONSTRAINT ux_education_module_versions_module_version UNIQUE (module_id, version),
    CONSTRAINT chk_education_module_versions_version_positive CHECK (version > 0),
    CONSTRAINT chk_education_module_versions_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'ARCHIVED', 'REJECTED'))
);

ALTER TABLE education_modules
    ADD CONSTRAINT fk_education_modules_current_published
    FOREIGN KEY (current_published_version_id)
    REFERENCES education_module_versions(id)
    ON DELETE SET NULL;

CREATE TABLE education_module_localizations (
    id                  BIGSERIAL PRIMARY KEY,
    module_version_id   BIGINT NOT NULL REFERENCES education_module_versions(id) ON DELETE CASCADE,
    language            VARCHAR(10) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    summary             VARCHAR(1000) NOT NULL,

    CONSTRAINT ux_education_module_localizations_version_language UNIQUE (module_version_id, language),
    CONSTRAINT chk_education_module_localizations_language CHECK (language IN ('EN', 'CS')),
    CONSTRAINT chk_education_module_localizations_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT chk_education_module_localizations_summary_not_blank CHECK (length(trim(summary)) > 0)
);

CREATE TABLE education_lessons (
    id          BIGSERIAL PRIMARY KEY,
    module_id   BIGINT NOT NULL REFERENCES education_modules(id) ON DELETE CASCADE,
    slug        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ux_education_lessons_module_slug UNIQUE (module_id, slug),
    CONSTRAINT chk_education_lessons_slug_not_blank CHECK (length(trim(slug)) > 0),
    CONSTRAINT chk_education_lessons_slug_format CHECK (slug = lower(slug) AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE education_lesson_versions (
    id                  BIGSERIAL PRIMARY KEY,
    module_version_id   BIGINT NOT NULL REFERENCES education_module_versions(id) ON DELETE CASCADE,
    lesson_id           BIGINT NOT NULL REFERENCES education_lessons(id) ON DELETE CASCADE,
    sort_order          INT NOT NULL,

    CONSTRAINT ux_education_lesson_versions_version_lesson UNIQUE (module_version_id, lesson_id),
    CONSTRAINT ux_education_lesson_versions_version_sort UNIQUE (module_version_id, sort_order),
    CONSTRAINT chk_education_lesson_versions_sort_positive CHECK (sort_order > 0)
);

CREATE TABLE education_lesson_localizations (
    id                  BIGSERIAL PRIMARY KEY,
    lesson_version_id   BIGINT NOT NULL REFERENCES education_lesson_versions(id) ON DELETE CASCADE,
    language            VARCHAR(10) NOT NULL,
    title               VARCHAR(200) NOT NULL,
    summary             VARCHAR(1000) NOT NULL,
    body_markdown       TEXT NOT NULL,

    CONSTRAINT ux_education_lesson_localizations_lesson_language UNIQUE (lesson_version_id, language),
    CONSTRAINT chk_education_lesson_localizations_language CHECK (language IN ('EN', 'CS')),
    CONSTRAINT chk_education_lesson_localizations_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT chk_education_lesson_localizations_summary_not_blank CHECK (length(trim(summary)) > 0),
    CONSTRAINT chk_education_lesson_localizations_body_not_blank CHECK (length(trim(body_markdown)) > 0)
);

CREATE TABLE education_lesson_completions (
    id                  BIGSERIAL PRIMARY KEY,
    patient_profile_id  BIGINT NOT NULL REFERENCES patient_profiles(id) ON DELETE CASCADE,
    module_version_id   BIGINT NOT NULL REFERENCES education_module_versions(id) ON DELETE CASCADE,
    lesson_version_id   BIGINT NOT NULL REFERENCES education_lesson_versions(id) ON DELETE CASCADE,
    completed_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT ux_education_lesson_completions_patient_lesson UNIQUE (patient_profile_id, lesson_version_id)
);

CREATE INDEX ix_education_modules_sort ON education_modules(sort_order, id);
CREATE INDEX ix_education_module_versions_status_created ON education_module_versions(status, created_at DESC);
CREATE INDEX ix_education_module_versions_author ON education_module_versions(author_user_id);
CREATE INDEX ix_education_lesson_versions_module_version ON education_lesson_versions(module_version_id, sort_order);
CREATE INDEX ix_education_lesson_completions_patient_module ON education_lesson_completions(patient_profile_id, module_version_id);
