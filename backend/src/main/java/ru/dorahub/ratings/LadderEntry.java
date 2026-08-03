package ru.dorahub.ratings;

import java.util.UUID;

/** Строка лестницы: рейтинг игрока в одном формате и сколько партий он сыграл. */
public record LadderEntry(UUID accountId, int rating, int games) {}
