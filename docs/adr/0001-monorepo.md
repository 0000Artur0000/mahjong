# ADR-0001: одна monorepo и короткие ветки

## Статус

Принято для foundation.

## Решение

Backend, Frontend, Vision, contracts и Infra живут в одном репозитории. Основная ветка — защищённая `main`; работа идёт короткими ветками по пунктам планов. Постоянные командные ветки и финальный большой merge не используются.

Полный workflow: [../../../plans/MONOREPO_WORKFLOW.md](../../../plans/MONOREPO_WORKFLOW.md).

