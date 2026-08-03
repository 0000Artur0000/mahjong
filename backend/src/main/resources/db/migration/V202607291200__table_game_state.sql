-- Игровое состояние стола: жеребьёвка и текущая партия.
-- Формат, состояние и версия остаются отдельными колонками из foundation: по ним идут выборки
-- и оптимистичная блокировка. Остальное читается только вместе со столом целиком.
ALTER TABLE game_table
    ADD COLUMN seating_seed BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN game_state JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX game_table_state_idx ON game_table (state, updated_at DESC);
