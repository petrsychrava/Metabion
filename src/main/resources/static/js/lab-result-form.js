document.querySelectorAll("[data-lab-result-row]").forEach(row => {
    const test = row.querySelector("[data-lab-test]");
    const unit = row.querySelector("[data-lab-unit]");
    const refresh = () => {
        const definition = (window.labCatalog || []).find(candidate => candidate.code === test.value);
        if (!definition) return;
        const previous = unit.value;
        unit.replaceChildren(...definition.allowedUnits.map(value => new Option(value, value)));
        if (definition.allowedUnits.includes(previous)) unit.value = previous;
    };
    test.addEventListener("change", refresh);
    refresh();
});
