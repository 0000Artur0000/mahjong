# Dorahub

Базовая monorepo для Dorahub: Spring backend, React frontend, Python Vision, общие контракты и локальная PostgreSQL.

## Структура

```text
backend/    Java/Spring и Flyway migrations
frontend/   React PWA shell
vision/     Python package для будущего Vision worker
contracts/  OpenAPI, JSON Schema и примеры
infra/      инфраструктурные конфигурации
docs/       ADR и локальная документация
```

Планы находятся в [../plans](../plans), правила веток — в [../plans/MONOREPO_WORKFLOW.md](../plans/MONOREPO_WORKFLOW.md).

## Быстрый старт

1. Скопировать `.env.example` в `.env`.
2. Запустить PostgreSQL и backend:

   ```bash
   docker compose up --build
   ```

3. Проверить:

   ```bash
   curl http://localhost:8080/actuator/health
   curl http://localhost:8080/api/v1/system/time
   ```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Vision smoke:

```bash
PYTHONPATH=vision/src python3 -m unittest discover -s vision/tests
```

## Что уже есть

- Gradle multi-project foundation с одним модулем `backend`.
- Spring Actuator и `GET /api/v1/system/time`.
- PostgreSQL 18 и первая additive Flyway migration.
- Минимальные OpenAPI/Event/Vision/Ruleset schemas.
- React shell и стандартный Python package без внешних зависимостей.
- CODEOWNERS/PR template placeholders для будущего Git-репозитория.

## Что намеренно не добавлено

- Git repository и remote.
- Пользователи, авторизация, Table/Score/Vision implementation.
- Закрытый датасет, ML weights и secrets.
- Kubernetes, Redis, Kafka и отдельные сервисы без готового контракта.

