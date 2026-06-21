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

@Entity
@Table(name = "daily_diet_log_deviations")
public class DailyDietLogDeviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_diet_log_id", nullable = false)
    private DailyDietLog dailyDietLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id")
    private DailyDietLogMeal meal;

    @Enumerated(EnumType.STRING)
    @Column(name = "deviation_category", nullable = false, length = 60)
    private DietDeviationCategory deviationCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DietDeviationSeverity severity;

    @Column(length = 1000)
    private String notes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected DailyDietLogDeviation() {
    }

    public DailyDietLogDeviation(
            DietDeviationCategory deviationCategory,
            DietDeviationSeverity severity,
            String notes,
            int sortOrder) {
        this.deviationCategory = deviationCategory;
        this.severity = severity;
        this.notes = notes;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public DailyDietLog getDailyDietLog() {
        return dailyDietLog;
    }

    public void setDailyDietLog(DailyDietLog dailyDietLog) {
        this.dailyDietLog = dailyDietLog;
    }

    public DailyDietLogMeal getMeal() {
        return meal;
    }

    public void setMeal(DailyDietLogMeal meal) {
        this.meal = meal;
    }

    public DietDeviationCategory getDeviationCategory() {
        return deviationCategory;
    }

    public void setDeviationCategory(DietDeviationCategory deviationCategory) {
        this.deviationCategory = deviationCategory;
    }

    public DietDeviationSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(DietDeviationSeverity severity) {
        this.severity = severity;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
