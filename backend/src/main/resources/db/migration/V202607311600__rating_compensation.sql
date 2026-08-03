-- Снятие рейтинга за отменённую партию.
--
-- Строки не удаляются и не правятся: лестница — это история, а не текущее значение.
-- Отмена — встречное начисление на ту же величину, поэтому сумма по партии остаётся нулевой,
-- а игроки, сыгравшие после, ничего не замечают: их партии считались от того рейтинга,
-- который тогда был, и переписывать его задним числом нельзя.
ALTER TABLE rating_change ADD COLUMN compensated BOOLEAN NOT NULL DEFAULT false;

-- Одна действующая запись на игрока за партию. Отменённые не мешают начислить заново:
-- партию доигрывают ещё раз, и это законный второй результат.
ALTER TABLE rating_change DROP CONSTRAINT rating_change_table_id_account_id_key;
CREATE UNIQUE INDEX rating_change_active_idx
    ON rating_change (table_id, account_id) WHERE NOT compensated;
