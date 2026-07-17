const refreshRowUnits = row => {
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
};

document.querySelectorAll("[data-lab-result-row]").forEach(refreshRowUnits);

const addResult = document.querySelector("[data-add-lab-result]");
const rows = document.querySelector("[data-lab-results]");
const rowTemplate = document.querySelector("#lab-result-row-template");
if (addResult && rows && rowTemplate) {
    addResult.addEventListener("click", () => {
        const index = rows.querySelectorAll("[data-lab-result-row]").length;
        const fragment = rowTemplate.content.cloneNode(true);
        fragment.querySelectorAll("[name]").forEach(field => {
            field.name = field.name.replace("__INDEX__", index);
        });
        const row = fragment.querySelector("[data-lab-result-row]");
        rows.append(fragment);
        refreshRowUnits(row);
        row.querySelector("[data-lab-test]").focus();
    });
}
