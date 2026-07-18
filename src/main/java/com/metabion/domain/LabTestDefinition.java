package com.metabion.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lab_test_definitions")
public class LabTestDefinition {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String code;
    @Column(name = "label_key", nullable = false, unique = true, length = 120)
    private String labelKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private LabTestCategory category;
    @Column(name = "canonical_unit", nullable = false, length = 40)
    private String canonicalUnit;
    @Column(name = "display_scale", nullable = false)
    private short displayScale;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @OneToMany(mappedBy = "testDefinition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<LabTestUnitDefinition> units = new ArrayList<>();

    protected LabTestDefinition() { }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getLabelKey() { return labelKey; }
    public LabTestCategory getCategory() { return category; }
    public String getCanonicalUnit() { return canonicalUnit; }
    public short getDisplayScale() { return displayScale; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
    public List<LabTestUnitDefinition> getUnits() { return units; }
}
