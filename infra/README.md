# Инфраструктура

Сейчас инфраструктура проекта — root `compose.yaml`: PostgreSQL, Spring backend
и nginx с собранным frontend.

```text
browser :5173 -> nginx -> /api/* -> backend -> postgres
```

Backend и PostgreSQL не публикуют порты на хост. Это сохраняет один origin для
cookie и CSRF и не требует локального CORS.

## Локальный запуск

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

Остановка сохраняет данные: `docker compose down`. Команда
`docker compose down -v` также удаляет локальный volume PostgreSQL.

Основные переменные перечислены в `.env.example`. В Git хранятся только
безопасные значения для локальной разработки; production-секреты должны
приходить из secret store среды.

## Production

Provider-specific OpenTofu/Ansible, TLS termination, backups и observability
появятся после выбора провайдера. До этого `compose.yaml` не считается
production deployment manifest.
