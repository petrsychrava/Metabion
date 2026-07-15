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
        row.querySelectorAll('[data-photo-upload-status], [data-photo-preview-link]').forEach((element) => element.remove());
        row.querySelectorAll('[data-photo-caption]').forEach((caption) => {
            caption.hidden = true;
        });
        updateDeviation(row);
    };

    let updateSectionStatuses = () => {};

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

    mealRows().forEach(updateDeviation);
    reindexMeals();
});
