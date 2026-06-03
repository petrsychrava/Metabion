package com.metabion.controller.web;

public record AppMenuItem(
        String label,
        String route,
        boolean planned,
        boolean dashboard,
        String description) {

    public String displayLabel() {
        return planned ? label + " - planned" : label;
    }

    public boolean linked() {
        return route != null && !planned;
    }
}
