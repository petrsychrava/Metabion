package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "lab_test_unit_definitions")
public class LabTestUnitDefinition {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "test_definition_id", nullable = false)
    private LabTestDefinition testDefinition;
    @Column(name = "unit_code", nullable = false, length = 40)
    private String unitCode;
    @Enumerated(EnumType.STRING) @Column(name = "conversion_type", nullable = false, length = 24)
    private LabUnitConversionType conversionType;
    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal multiplier;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected LabTestUnitDefinition() { }
    public Long getId() { return id; }
    public LabTestDefinition getTestDefinition() { return testDefinition; }
    public String getUnitCode() { return unitCode; }
    public LabUnitConversionType getConversionType() { return conversionType; }
    public BigDecimal getMultiplier() { return multiplier; }
    public int getSortOrder() { return sortOrder; }
}
