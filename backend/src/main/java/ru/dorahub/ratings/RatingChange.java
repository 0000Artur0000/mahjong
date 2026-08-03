package ru.dorahub.ratings;

import java.time.Instant;
import java.util.UUID;

/**
 * Изменение рейтинга за одну партию.
 *
 * <p>Хранится как есть: на вопрос «почему я потерял 12» отвечает строка, а не пересчёт.
 */
public record RatingChange(
    UUID tableId,
    UUID accountId,
    String format,
    int place,
    int delta,
    int ratingAfter,
    Instant at) {}
