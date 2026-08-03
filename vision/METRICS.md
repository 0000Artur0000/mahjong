# Метрики Vision

Актуальные результаты прогонов находятся в [README.md](README.md). Здесь
зафиксировано, что именно измерять и когда результат можно сравнивать.

## Три независимых уровня

1. **Localization:** найден ли каждый физический тайл и нет ли ложных объектов.
2. **Classification:** верно ли прочитан номинал найденного лицевого тайла.
3. **Grouping:** верно ли назначены роль, место игрока и открытая связка.

Нельзя выдавать количество CountGD++ proposals за detection recall: для recall
нужны вручную размеченные instance boxes. Точно так же nominal accuracy считается
только по matched detections, а не по всем тайлам сцены.

## Локальная оценка ролей

`vision/scripts/score_layout.py` сравнивает `groups.json` с проверенными файлами
из `test/roles-truth`:

```bash
sed -e 's/\.jfif\.jpg"/.jfif"/g' -e 's/\.jpg\.jpg"/.jpg"/g' \
  ../test/experiments/countgdpp-old11-layout-v2-clean/groups.json | \
PYTHONPATH=vision/src:. python3 vision/scripts/score_layout.py score \
  /dev/stdin ../test/roles-truth
```

Отчёт содержит precision/recall по роли и три сводных показателя:

- `falseRole` — доля сопоставленных тайлов с уверенно неверной ролью;
- `silence` — доля сопоставленных тайлов, оставленных в `other`/`noise`;
- `missedTiles` — доля размеченных тайлов без сопоставленной детекции.

`unknown` исключается из оценки. Кадр участвует только после ручной установки
`"reviewed": true`; seed из собственного prediction не считается независимой
разметкой.

Текущий эталон: 8 кадров, 545 оцениваемых тайлов и 60 `unknown`.
`falseRole = 0.215`, `silence = 0.110`, `missedTiles = 0.213`. Самые слабые
классы — `opponent_hand` (recall 0.426), `dead_wall` и `dora` (по текущему
маленькому эталону recall 0).

## Продуктовый отчёт — schema 1

Единица отчёта — scene. Ground truth содержит tile instances и groups;
`predictions` уже сопоставлены с ground truth, а лишние detections считаются
отдельно. `dorahub_vision.evaluation.evaluate` возвращает:

- automation coverage = accepted scenes / all scenes;
- false accept = accepted scenes с ошибкой detection/class/group / accepted;
- exact scene = scenes без missing/extra/wrong tiles и grouping errors / all;
- detection precision и recall;
- classification accuracy по matched detections;
- grouping accuracy по проверенным группам;
- reshoot rate;
- mean corrected tiles и nearest-rank p50/p90 времени до подтверждения.

Каждая rate хранит `numerator`, `denominator` и `value`; при нулевом denominator
`value` равен `null`. Candidate и production сравниваются только на одном
dataset split, schema version и зафиксированном threshold.

До независимого session-separated validation set и calibration/rejection gate
автоматическое принятие результата выключено: ML только предлагает разбор
человеку.
