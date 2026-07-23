document.addEventListener('DOMContentLoaded', () => {
    const form = document.querySelector('[data-onboarding-form]');
    if (!form) return;

    const liveRegion = form.querySelector('#onboarding-live');
    const errorSummary = form.querySelector('#onboarding-errors');
    const labChoice = form.querySelector('[data-labs-choice]');
    const labFields = form.querySelector('[data-lab-fields]');
    const hasValue = (element) => element.value.trim() !== '';
    const setTextIfChanged = (element, text) => {
        if (element && element.textContent !== text) element.textContent = text;
    };
    const announce = (message) => {
        if (!liveRegion) return;
        liveRegion.textContent = '';
        window.requestAnimationFrame(() => { liveRegion.textContent = message; });
    };
    const errorTargetFor = (link) => document.getElementById(link.dataset.errorTargetId);

    const setLabsVisible = () => {
        if (!labChoice || !labFields) return;
        const includeLabs = labChoice.querySelector('[name="includeLabs"]:checked')?.value === 'true';
        labFields.hidden = !includeLabs;
        labFields.querySelectorAll('input, select, textarea').forEach((element) => {
            element.disabled = !includeLabs;
            if (!includeLabs) element.value = '';
        });
    };

    const sectionHasError = (section) => {
        if (section.querySelector('.error') || section.querySelector('[aria-invalid="true"]')) return true;
        if (!errorSummary) return false;
        return Array.from(errorSummary.querySelectorAll('[data-error-target-id]'))
            .some((link) => {
                const target = errorTargetFor(link);
                return target !== null && section.contains(target);
            });
    };

    const sectionState = (section) => {
        if (section.dataset.section === 'labs') {
            const includeLabs = labChoice?.querySelector('[name="includeLabs"]:checked')?.value === 'true';
            if (!includeLabs) return 'optional';
            const anyLabValue = labFields !== null
                && Array.from(labFields.querySelectorAll('input, select, textarea')).some(hasValue);
            return anyLabValue ? 'added' : 'inProgress';
        }
        const requiredFields = Array.from(section.querySelectorAll('[data-required-field="true"]'));
        const filled = requiredFields.filter(hasValue).length;
        if (filled === 0) return 'notStarted';
        if (filled === requiredFields.length) return 'complete';
        return 'inProgress';
    };

    const statusTextFor = (state) => {
        switch (state) {
            case 'complete': return form.dataset.statusComplete;
            case 'inProgress': return form.dataset.statusInProgress;
            case 'optional': return form.dataset.statusOptional;
            case 'added': return form.dataset.statusAdded;
            case 'needsAttention': return form.dataset.statusNeedsAttention;
            default: return form.dataset.statusNotStarted;
        }
    };

    const updateSectionStatuses = () => {
        const states = {};
        form.querySelectorAll('details[data-section]').forEach((section) => {
            const state = sectionHasError(section) ? 'needsAttention' : sectionState(section);
            states[section.dataset.section] = state;
            setTextIfChanged(section.querySelector('[data-section-status]'), statusTextFor(state));
        });
        const completed = ['condition', 'treatment']
                .filter((key) => states[key] === 'complete').length;
        const progressText = form.dataset.requiredProgressTemplate
                .replace('{0}', String(completed))
                .replace('{1}', '2');
        setTextIfChanged(form.querySelector('[data-required-progress]'), progressText);
    };

    labChoice?.addEventListener('change', () => {
        setLabsVisible();
        updateSectionStatuses();
    });
    form.addEventListener('input', updateSectionStatuses);
    form.addEventListener('change', updateSectionStatuses);
    setLabsVisible();
    updateSectionStatuses();

    let invalidFocusScheduled = false;
    form.addEventListener('invalid', (event) => {
        if (invalidFocusScheduled) return;
        invalidFocusScheduled = true;
        const invalidControl = event.target;
        invalidControl.closest('details[data-section]')?.setAttribute('open', '');
        window.requestAnimationFrame(() => {
            invalidControl.focus();
            invalidFocusScheduled = false;
        });
    }, true);

    if (errorSummary) {
        const firstErrorLink = errorSummary.querySelector('[data-error-target-id]');
        const target = firstErrorLink ? errorTargetFor(firstErrorLink) : null;
        target?.closest('details[data-section]')?.setAttribute('open', '');
        target?.focus();
        announce(form.dataset.statusNeedsAttention);
    }
});
