ALTER TABLE daily_diet_log_deviations
    ADD COLUMN meal_id BIGINT;

ALTER TABLE daily_diet_log_deviations
    ADD CONSTRAINT fk_daily_diet_log_deviations_meal_log
        FOREIGN KEY (meal_id, daily_diet_log_id)
        REFERENCES daily_diet_log_meals(id, daily_diet_log_id)
        ON DELETE CASCADE;

CREATE INDEX ix_daily_diet_log_deviations_meal
    ON daily_diet_log_deviations(meal_id);
