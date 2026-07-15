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
@Table(name = "daily_diet_log_meals")
public class DailyDietLogMeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_diet_log_id", nullable = false)
    private DailyDietLog dailyDietLog;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 40)
    private MealType mealType;

    @Column(name = "food_description", length = 500)
    private String foodDescription;

    @Column(length = 1000)
    private String notes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected DailyDietLogMeal() {
    }

    public DailyDietLogMeal(
            MealType mealType,
            String foodDescription,
            String notes,
            int sortOrder) {
        this.mealType = mealType;
        this.foodDescription = foodDescription;
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

    public MealType getMealType() {
        return mealType;
    }

    public void setMealType(MealType mealType) {
        this.mealType = mealType;
    }

    public String getFoodDescription() {
        return foodDescription;
    }

    public void setFoodDescription(String foodDescription) {
        this.foodDescription = foodDescription;
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
