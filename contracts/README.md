# Контракты

Этот каталог — источник общих форматов между backend, frontend и Vision.

| Каталог | Что хранится |
| --- | --- |
| `openapi/` | HTTP API в OpenAPI 3.1 |
| `events/` | JSON Schema событий стола и интеграций |
| `vision/` | запросы и результаты Vision worker |
| `rulesets/` | схема неизменяемого снимка правил |

## Изменение HTTP API

1. Сначала изменить `openapi/api.yaml`.
2. Обновить backend producer и frontend consumer.
3. Сгенерировать типы frontend и запустить проверки.

```bash
./scripts/openapi.sh validate
./scripts/openapi.sh generate-frontend
cd frontend && npm run check
```

Сгенерированный `frontend/src/api/schema.d.ts` вручную не редактируется.
Удаление поля, endpoint или допустимого значения считается потенциально
ломающим изменением и проверяется перед merge:

```bash
./scripts/openapi.sh breaking path/to/baseline.yaml
```

JSON Schema проверяются тестами компонентов, которые их читают. Версию схемы
нужно менять вместе с producer и consumer; неизвестные поля consumer должен
игнорировать, если конкретная схема не требует обратного.
