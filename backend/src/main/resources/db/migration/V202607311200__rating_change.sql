-- Рейтинг: одна append-only запись на игрока за партию.
--
-- Текущий рейтинг — последняя строка игрока в лестнице, поэтому отдельной таблицы с
-- текущим значением нет: нечему разъезжаться с историей. Внешних ключей нет намеренно —
-- game_table и app_user принадлежат другим модулям, а обезличивание аккаунта не должно
-- стирать чужие дельты: сумма изменений в партии равна нулю и обязана такой остаться.
CREATE TABLE rating_change (
    seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_id UUID NOT NULL,
    account_id UUID NOT NULL,
    format VARCHAR(32) NOT NULL,
    place SMALLINT NOT NULL CHECK (place BETWEEN 1 AND 4),
    delta INTEGER NOT NULL,
    rating_after INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (table_id, account_id)
);

-- Лестница и «мой рейтинг» читают последнюю строку игрока в формате.
CREATE INDEX rating_change_ladder_idx ON rating_change (format, account_id, seq DESC);
