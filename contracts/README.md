# Contracts

Единый источник схем между командами:

- `openapi/` — HTTP API;
- `events/` — realtime/integration events;
- `vision/` — Backend ↔ Vision;
- `rulesets/` — машинно-проверяемые snapshots.

Изменение общего формата сначала вливается отдельным `contract/...` PR. Generated clients не редактируются вручную.

`./scripts/openapi.sh validate` проверяет HTTP-контракт. Команда `generate-frontend`
генерирует TypeScript Fetch client, а `breaking <baseline.yaml>` проверяет совместимость перед merge.
