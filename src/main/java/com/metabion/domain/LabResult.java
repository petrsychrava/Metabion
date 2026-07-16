package com.metabion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Entity
@Table(name = "lab_results")
public class LabResult {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "result_set_id", nullable = false)
    private LabResultSet resultSet;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "test_definition_id", nullable = false)
    private LabTestDefinition testDefinition;
    @Column(name = "reported_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal reportedValue;
    @Column(name = "reported_unit", nullable = false, length = 40)
    private String reportedUnit;
    @Column(name = "canonical_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal canonicalValue;
    @Column(name = "canonical_unit", nullable = false, length = 40)
    private String canonicalUnit;
    @Column(name = "reference_lower", precision = 18, scale = 6)
    private BigDecimal referenceLower;
    @Column(name = "reference_upper", precision = 18, scale = 6)
    private BigDecimal referenceUpper;

    protected LabResult() { }

    public LabResult(LabResultSet resultSet, LabTestDefinition testDefinition, BigDecimal reportedValue,
                     String reportedUnit, BigDecimal canonicalValue, String canonicalUnit,
                     BigDecimal referenceLower, BigDecimal referenceUpper) {
        this.resultSet = Objects.requireNonNull(resultSet);
        this.testDefinition = Objects.requireNonNull(testDefinition);
        this.reportedValue = toDatabaseScale(reportedValue);
        this.reportedUnit = Objects.requireNonNull(reportedUnit);
        this.canonicalValue = toDatabaseScale(canonicalValue);
        this.canonicalUnit = Objects.requireNonNull(canonicalUnit);
        this.referenceLower = referenceLower == null ? null : toDatabaseScale(referenceLower);
        this.referenceUpper = referenceUpper == null ? null : toDatabaseScale(referenceUpper);
    }

    private static BigDecimal toDatabaseScale(BigDecimal value) {
        return Objects.requireNonNull(value).setScale(6, RoundingMode.UNNECESSARY);
    }

    public Long getId() { return id; }
    public LabResultSet getResultSet() { return resultSet; }
    public LabTestDefinition getTestDefinition() { return testDefinition; }
    public BigDecimal getReportedValue() { return reportedValue; }
    public String getReportedUnit() { return reportedUnit; }
    public BigDecimal getCanonicalValue() { return canonicalValue; }
    public String getCanonicalUnit() { return canonicalUnit; }
    public BigDecimal getReferenceLower() { return referenceLower; }
    public BigDecimal getReferenceUpper() { return referenceUpper; }
}
