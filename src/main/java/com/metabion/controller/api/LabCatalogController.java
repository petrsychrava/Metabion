package com.metabion.controller.api;

import com.metabion.dto.LabTestDefinitionResponse;
import com.metabion.service.LabCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LabCatalogController {

    private final LabCatalogService catalog;

    public LabCatalogController(LabCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/lab-tests")
    public List<LabTestDefinitionResponse> list() {
        return catalog.listActive();
    }
}
