# ML sources

## Riichi recognition reference

[`2409324124/riichi-mahjong-recognition`](https://github.com/2409324124/riichi-mahjong-recognition)
проверен на commit `eda5c1b8f9de9707479ed739489ff54a2dff8411`.
В репозитории нет заявленных dataset, weights, release и отдельного `LICENSE`.
`pyproject.toml` декларирует MIT, но это не заменяет license text.

Его recognition-код не переносится: generic COCO `yolov8n.pt` не является
детектором маджонга, а `TileClassifier` при отсутствии шаблонов возвращает
случайный tile ID с confidence `0.5`. Недоказанная метрика `mAP50=99.5%` также
не используется.

Dorahub независимо реализует только совместимый Riichi vocabulary/trust
boundary: 34 базовых вида, красные пятёрки, common dataset aliases, dora
rotation и зоны hand/dora/discard.

## Legacy layout baseline

Geometry/layout основан на [`haitaks/mahjong`](https://github.com/haitaks/mahjong)
commit `4955fb61e3d9dab7e6a3640ce2a63759ca0da27f`
(`mahjong-layout`, author `rosalvak`). Upstream `pyproject.toml` declares MIT;
отдельного `LICENSE` файла на этом commit нет.

В Dorahub перенесены идеи `TileBox → scale-aware DBSCAN → hand/discard/wall`
и параметры калибровки. Реализация адаптирована на Python stdlib и добавляет
валидацию normalized boxes и лимит detections. OCR, classifier, CLI и
visualization не копируются. CC BY 4.0 dataset импортирован отдельно в
`vision/datasets/haitaks-mahjong-layout-v2` как single-class detector baseline.
