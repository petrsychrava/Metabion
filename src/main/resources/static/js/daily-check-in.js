document.addEventListener('DOMContentLoaded', () => {
    const form = document.querySelector('.daily-check-in-form');
    if (!form) return;

    const mealList = form.querySelector('[data-meal-list]');
    const addMealButton = form.querySelector('[data-add-meal]');
    const liveRegion = form.querySelector('#daily-check-in-live');
    const errorSummary = form.querySelector('#daily-check-in-errors');
    const csrf = form.querySelector('input[name="_csrf"]');

    const format = (template, value) => template.replace('{0}', String(value));
    const announce = (message) => {
        if (!liveRegion) return;
        liveRegion.textContent = '';
        window.requestAnimationFrame(() => {
            liveRegion.textContent = message;
        });
    };
    const mealRows = () => mealList ? Array.from(mealList.querySelectorAll('[data-meal-row]')) : [];

    const updateMealAttributes = (row, mealIndex) => {
        row.dataset.mealIndex = String(mealIndex);
        const mealNumber = mealIndex + 1;
        const number = row.querySelector('[data-meal-number]');
        if (number) number.textContent = String(mealNumber);

        row.querySelectorAll('[name]').forEach((element) => {
            element.name = element.name.replace(/meals\[\d+]/g, `meals[${mealIndex}]`);
        });
        row.querySelectorAll('[id]').forEach((element) => {
            element.id = element.id.replace(/meals\d+/g, `meals${mealIndex}`);
        });
        row.querySelectorAll('[for]').forEach((element) => {
            element.htmlFor = element.htmlFor.replace(/meals\d+/g, `meals${mealIndex}`);
        });
        const remove = row.querySelector('[data-remove-meal]');
        if (remove) remove.setAttribute('aria-label', format(form.dataset.removeMealTemplate, mealNumber));
        const notes = row.querySelector('[name$=".notes"]');
        if (notes) notes.setAttribute('aria-label', format(form.dataset.mealNotesTemplate, mealNumber));
        const upload = row.querySelector('.diet-photo-upload');
        if (upload) upload.setAttribute('aria-label', format(form.dataset.mealPhotoTemplate, mealNumber));
        row.querySelectorAll('[data-photo-caption] input').forEach((caption) => {
            caption.setAttribute('aria-label', format(form.dataset.mealCaptionTemplate, mealNumber));
        });

        row.querySelectorAll('[data-photo-row]').forEach((photoRow, photoIndex) => {
            photoRow.querySelectorAll('[name]').forEach((element) => {
                element.name = element.name.replace(/photoReferences\[\d+]/g, `photoReferences[${photoIndex}]`);
            });
            photoRow.querySelectorAll('[id]').forEach((element) => {
                element.id = element.id.replace(/photoReferences\d+/g, `photoReferences${photoIndex}`);
            });
            photoRow.querySelectorAll('[for]').forEach((element) => {
                element.htmlFor = element.htmlFor.replace(/photoReferences\d+/g, `photoReferences${photoIndex}`);
            });
        });
    };

    const reindexMeals = () => mealRows().forEach(updateMealAttributes);

    const updateDeviation = (row) => {
        const category = row.querySelector('[data-deviation-category]');
        const details = row.querySelector('[data-deviation-details]');
        if (!category || !details) return;
        details.hidden = !category.value;
        if (!category.value) {
            details.querySelectorAll('select, textarea').forEach((element) => {
                element.value = '';
            });
        }
    };

    const resetMealRow = (row) => {
        row.querySelectorAll('input:not([type="file"]), select, textarea').forEach((element) => {
            element.value = '';
        });
        row.querySelectorAll('input[type="file"]').forEach((element) => {
            element.value = '';
        });
        Array.from(row.querySelectorAll('[data-photo-row]')).slice(1).forEach((photoRow) => photoRow.remove());
        row.querySelectorAll('[data-photo-upload-status], [data-photo-preview-link], [data-photo-upload-error]')
                .forEach((element) => element.remove());
        row.querySelectorAll('[data-photo-caption]').forEach((caption) => {
            caption.hidden = true;
        });
        updateDeviation(row);
    };

    const hasValue = (element) => Boolean(element && element.value.trim());
    const checkedValue = (selector) => form.querySelector(`${selector}:checked`)?.value || '';

    const stateForDiet = () => {
        const date = hasValue(form.querySelector('[name="logDate"]'));
        const adherence = hasValue(form.querySelector('[name="adherenceLevel"]'));
        const appetite = hasValue(form.querySelector('[name="appetiteLevel"]'));
        if (!adherence && !appetite) return 'notStarted';
        return date && adherence && appetite ? 'complete' : 'inProgress';
    };

    const stateForMeasurements = () => {
        const prefixes = ['glucoseMeasurement', 'ketoneMeasurement'];
        let started = false;
        let complete = true;
        prefixes.forEach((prefix) => {
            const value = hasValue(form.querySelector(`[name="${prefix}.value"]`));
            const time = hasValue(form.querySelector(`[name="${prefix}.measuredTime"]`));
            const context = hasValue(form.querySelector(`[name="${prefix}.context"]`));
            const notes = hasValue(form.querySelector(`[name="${prefix}.notes"]`));
            const rowStarted = value || time || context || notes;
            started ||= rowStarted;
            if (rowStarted && !(value && time && context)) complete = false;
        });
        if (!started) return 'notStarted';
        return complete ? 'complete' : 'inProgress';
    };

    const stateForMeals = () => {
        let started = false;
        let complete = true;
        mealRows().forEach((row) => {
            const mealType = hasValue(row.querySelector('[name$=".mealType"]'));
            const description = hasValue(row.querySelector('[name$=".foodDescription"]'));
            const notes = hasValue(row.querySelector('[name$=".notes"]'));
            const deviation = hasValue(row.querySelector('[data-deviation-category]'));
            const severity = hasValue(row.querySelector('[name$=".deviation.severity"]'));
            const photo = Array.from(row.querySelectorAll('[name$=".uploadId"]')).some(hasValue);
            const rowStarted = mealType || description || notes || deviation || severity || photo;
            started ||= rowStarted;
            if (rowStarted && (!mealType || (deviation && !severity) || (!deviation && severity))) complete = false;
        });
        if (!started) return 'notStarted';
        return complete ? 'complete' : 'inProgress';
    };

    const stateForSymptoms = () => {
        const flare = checkedValue('[name="flareState"]');
        const requiredAnswers = Array.from(form.querySelectorAll('[data-required-symptom="true"]'));
        const answered = requiredAnswers.filter((element) => {
            if (element.type === 'radio' || element.type === 'checkbox') return element.checked;
            return hasValue(element);
        }).length;
        const anyAnswer = Array.from(form.querySelectorAll('[name^="symptomAnswers"]'))
                .some((element) => !element.name.endsWith('.questionId') && hasValue(element));
        if (!flare && !anyAnswer) return 'notStarted';
        return flare && answered === requiredAnswers.length ? 'complete' : 'inProgress';
    };

    const statusText = (state) => ({
        notStarted: form.dataset.statusNotStarted,
        inProgress: form.dataset.statusInProgress,
        complete: form.dataset.statusComplete,
        needsAttention: form.dataset.statusNeedsAttention
    })[state];

    const hasLinkedError = (section) => Array.from(errorSummary?.querySelectorAll('a[href^="#"]') || [])
            .some((link) => form.querySelector(link.getAttribute('href'))?.closest('[data-section]') === section);

    const updateSectionStatuses = () => {
        const states = {
            diet: stateForDiet(),
            measurements: stateForMeasurements(),
            meals: stateForMeals(),
            symptoms: stateForSymptoms()
        };
        const displayStates = {...states};
        form.querySelectorAll('[data-section]').forEach((section) => {
            const hasErrors = Boolean(section.querySelector('.error, [aria-invalid="true"]')) || hasLinkedError(section);
            const state = hasErrors ? 'needsAttention' : states[section.dataset.section];
            displayStates[section.dataset.section] = state;
            const status = section.querySelector('[data-section-status]');
            if (status) status.textContent = statusText(state);
        });
        const requiredComplete = ['diet', 'symptoms']
                .filter((key) => displayStates[key] === 'complete').length;
        const progress = form.querySelector('[data-required-progress]');
        if (progress) {
            progress.textContent = form.dataset.requiredProgressTemplate
                    .replace('{0}', String(requiredComplete))
                    .replace('{1}', '2');
        }
    };

    form.addEventListener('input', updateSectionStatuses);
    form.addEventListener('change', updateSectionStatuses);

    addMealButton?.addEventListener('click', () => {
        const source = mealRows().at(-1);
        if (!source) return;
        const clone = source.cloneNode(true);
        resetMealRow(clone);
        mealList.appendChild(clone);
        reindexMeals();
        const number = mealRows().length;
        clone.querySelector('select[name$=".mealType"]')?.focus();
        announce(format(form.dataset.mealAddedTemplate, number));
        updateSectionStatuses();
    });

    mealList?.addEventListener('change', (event) => {
        const category = event.target.closest('[data-deviation-category]');
        if (category) updateDeviation(category.closest('[data-meal-row]'));
        updateSectionStatuses();
    });

    mealList?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-remove-meal]');
        if (!button) return;
        const row = button.closest('[data-meal-row]');
        if (!row) return;
        const removedNumber = Number(row.dataset.mealIndex || '0') + 1;
        const rows = mealRows();
        if (rows.length === 1) {
            resetMealRow(row);
            row.querySelector('select[name$=".mealType"]')?.focus();
        } else {
            const focusTarget = row.previousElementSibling?.querySelector('[data-remove-meal]') || addMealButton;
            row.remove();
            reindexMeals();
            focusTarget?.focus();
        }
        announce(format(form.dataset.mealRemovedTemplate, removedNumber));
        updateSectionStatuses();
    });

    const nextEmptyPhotoRow = (mealRow) => Array.from(mealRow.querySelectorAll('[data-photo-row]'))
            .find((row) => !row.querySelector('input[name$=".uploadId"]')?.value);

    const appendPhotoRow = (mealRow, upload) => {
        let row = nextEmptyPhotoRow(mealRow);
        if (!row) {
            const mealIndex = Number(mealRow.dataset.mealIndex || '0');
            const photoIndex = mealRow.querySelectorAll('[data-photo-row]').length;
            row = document.createElement('div');
            row.className = 'photo-reference-row';
            row.setAttribute('data-photo-row', '');
            row.innerHTML = `<input type="hidden" name="meals[${mealIndex}].photoReferences[${photoIndex}].uploadId">
                <label class="field" data-photo-caption><span>${mealList.dataset.captionLabel}</span>
                    <input name="meals[${mealIndex}].photoReferences[${photoIndex}].caption"></label>`;
            mealRow.querySelector('[data-photo-rows]').appendChild(row);
        }

        row.querySelector('input[name$=".uploadId"]').value = upload.uploadId;
        const filename = upload.originalFilename || mealList.dataset.uploadedFallback;
        let status = row.querySelector('[data-photo-upload-status]');
        if (!status) {
            status = document.createElement('p');
            status.className = 'hint photo-upload-status';
            status.setAttribute('data-photo-upload-status', '');
            row.prepend(status);
        }
        status.textContent = `${mealList.dataset.uploadedLabel}: ${filename}`;

        if (upload.contentUrl) {
            let link = row.querySelector('[data-photo-preview-link]');
            if (!link) {
                link = document.createElement('a');
                link.className = 'photo-preview-link';
                link.setAttribute('data-photo-preview-link', '');
                link.target = '_blank';
                link.rel = 'noopener';
                status.after(link);
            }
            link.href = upload.contentUrl;
            link.innerHTML = `<img class="diet-photo-preview" data-photo-preview alt="">`;
            const image = link.querySelector('img');
            image.src = upload.contentUrl;
            image.alt = filename;
        }

        const caption = row.querySelector('[data-photo-caption]');
        if (caption) caption.hidden = false;
        reindexMeals();
        announce(format(form.dataset.photoSuccessTemplate, filename));
    };

    mealList?.addEventListener('change', async (event) => {
        const input = event.target.closest('.diet-photo-upload');
        if (!input || !csrf) return;
        const mealRow = input.closest('[data-meal-row]');
        if (!mealRow) return;
        mealRow.querySelector('[data-photo-upload-error]')?.remove();

        for (const file of input.files) {
            const formData = new FormData();
            formData.append('file', file);
            try {
                const response = await fetch('/api/diet-log-photos/uploads', {
                    method: 'POST',
                    headers: {'X-XSRF-TOKEN': csrf.value},
                    body: formData
                });
                if (!response.ok) throw new Error(`Upload returned ${response.status}`);
                appendPhotoRow(mealRow, await response.json());
            } catch (error) {
                let status = mealRow.querySelector('[data-photo-upload-error]');
                if (!status) {
                    status = document.createElement('p');
                    status.className = 'error';
                    status.setAttribute('data-photo-upload-error', '');
                    input.closest('label').after(status);
                }
                status.textContent = form.dataset.photoFailure;
                announce(form.dataset.photoFailure);
            }
        }
        input.value = '';
        updateSectionStatuses();
    });

    const logDateInput = form.querySelector('[data-log-date]');
    const loadedDate = form.dataset.loadedDate;
    let submitting = false;

    const snapshot = () => {
        const entries = [];
        new FormData(form).forEach((value, key) => {
            if (key !== '_csrf' && key !== 'logDate' && !(value instanceof File)) {
                entries.push([key, String(value)]);
            }
        });
        return JSON.stringify(entries.sort(([a], [b]) => a.localeCompare(b)));
    };
    const baseline = snapshot();
    const isDirty = () => snapshot() !== baseline;

    logDateInput?.addEventListener('change', () => {
        if (!logDateInput.value || logDateInput.value === loadedDate) return;
        if (isDirty() && !window.confirm(form.dataset.unsavedDateConfirm)) {
            logDateInput.value = loadedDate;
            return;
        }
        const target = new URL(window.location.href);
        target.searchParams.set('date', logDateInput.value);
        window.location.assign(target.toString());
    });

    form.addEventListener('submit', () => {
        submitting = true;
    });

    window.addEventListener('beforeunload', (event) => {
        if (submitting || !isDirty()) return;
        event.preventDefault();
        event.returnValue = form.dataset.unsavedLeaveWarning;
    });

    if (errorSummary) {
        const firstErrorTarget = errorSummary.querySelector('a[href^="#"]')?.getAttribute('href');
        const target = firstErrorTarget ? form.querySelector(firstErrorTarget) : null;
        target?.closest('[data-section]')?.setAttribute('open', '');
        target?.focus();
    }

    mealRows().forEach(updateDeviation);
    reindexMeals();
    updateSectionStatuses();
});
