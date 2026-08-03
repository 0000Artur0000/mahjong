package ru.dorahub.ratings;

import java.util.UUID;

/**
 * Игрок и его место в законченной партии.
 *
 * @param place 1..4; равные значения означают делённое место
 */
public record RatedPlace(UUID accountId, int place) {}
