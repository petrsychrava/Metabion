package com.metabion.controller.web;

public record AppMenuItem(
        String label,
        String route,
        boolean planned,
        boolean dashboard,
        String description,
        String plannedSuffix) {

    public AppMenuItem(String label, String route, boolean planned, boolean dashboard, String description) {
        this(label, route, planned, dashboard, description, " - planned");
    }

    public String displayLabel() {
        return planned ? label + plannedSuffix : label;
    }

    public boolean linked() {
        return route != null && !planned;
    }
}
