ALTER TABLE daily_diet_log_meals
    DROP CONSTRAINT chk_daily_diet_log_meals_food_category;

ALTER TABLE daily_diet_log_meals
    DROP COLUMN food_category;
