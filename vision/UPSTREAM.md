# ML sources

Краткий статус всей ML-части находится в [README.md](README.md). Здесь
фиксируются upstream, commit, лицензия и решение об использовании.

## Current tile proposals: CountGD++

[`niki-amini-naieni/CountGDPlusPlus`](https://github.com/niki-amini-naieni/CountGDPlusPlus)
проверен на commit `345447b8848b5cc3554ae3712b61c57de89162d5` и содержит MIT
license. Репозиторий и checkpoints хранятся локально вне Dorahub в
`experiments/countgdpp`.

CountGD++ принят как текущий reference proposal detector: prompt и exemplar
дают лучший визуальный охват полного стола среди проверенных zero/few-shot
вариантов. В Dorahub не копируется модель; сохраняется её JSON-выход, после
чего собственный `split_tile_rows.py` разделяет слипшиеся ряды, а layout parser
назначает роли. Количество proposals не считается precision/recall без
независимой instance-разметки.

## Contours: Segment Anything 2

[`facebookresearch/sam2`](https://github.com/facebookresearch/sam2) проверен на
commit `2b90b9f5ceec907a1c18123530e92e794ad901a4`, Apache-2.0. Модель и веса
остаются вне Git.

SAM2 используется только после detector box для перспективного контура тайла.
Контур проверяется относительно prompt; при плохой маске используется
CountGD++ polygon. SAM2 не создаёт пропущенные detector proposals и не заменяет
дообучение локализатора.

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

## Synthetic smoke assets

[`FluffyStuff/riichi-mahjong-tiles`](https://github.com/FluffyStuff/riichi-mahjong-tiles)
commit `26e127ba2117f45cdce5ea0225748cc0cfad3169` содержит public-domain
PNG/SVG assets. Они используются только для проверки 37-class dataset/training
pipeline. Synthetic metrics не считаются доказательством качества на фото
физических тайлов.

## Mobile Riichi photos and LiteRT artifact

[`linkoon2019/Mahjong_Caculator_YOLO_Android`](https://github.com/linkoon2019/Mahjong_Caculator_YOLO_Android)
commit `8cb4fc99ee3c8eecfc7064cf4a0a67d6992c386e`, release `v1.0.0`.
Release APK SHA-256:
`08ec9bbf2e63966e57e9b0a138d611ee775caece9f7178c3cdba82f39363081a`.
Extracted `mahjong_yolo.tflite` SHA-256:
`04e92b1b58256806d1bfb301e2f4d212469532d8c688423636879e48c340dbe5`.

Artifact output is 38 classes: 34 base faces, three red fives and `unknown`.
Its honor order is nonstandard and explicitly remapped:
`1z→E`, `2z→S`, `3z→W`, `4z→N`, `5z→C`, `6z→F`, `7z→P`.
The Android letterbox postprocessing treats normalized boxes as pixels; Dorahub
does not copy that bug and independently validates the model signature.

The linked Kaggle dataset
[`shinz114514/mahjong-hand-photos-taken-with-mobile-camera`](https://www.kaggle.com/datasets/shinz114514/mahjong-hand-photos-taken-with-mobile-camera)
version 1 contains 257 unannotated PNG scenes (archive SHA-256
`35c076754dbac0a3d4345780cf731272f9df2b4e1168d0222b0b161f611044df`);
Kaggle metadata declares MIT. It is a real-photo labeling source, not a ready
YOLO dataset.

## Legacy layout baseline

Geometry/layout основан на [`haitaks/mahjong`](https://github.com/haitaks/mahjong)
commit `4955fb61e3d9dab7e6a3640ce2a63759ca0da27f`
(`mahjong-layout`, author `rosalvak`). Upstream `pyproject.toml` declares MIT;
отдельного `LICENSE` файла на этом commit нет.

В Dorahub перенесены идеи `TileBox → scale-aware DBSCAN → hand/discard/wall`
и параметры калибровки. Реализация адаптирована на Python stdlib и добавляет
валидацию normalized boxes, лимит detections и ограничения структуры риичи.
OCR, classifier, CLI и visualization не копируются. CC BY 4.0 dataset импортирован отдельно в
`vision/datasets/haitaks-mahjong-layout-v2` как single-class detector baseline.

Повторный GitHub-аудит 2026-07-30 не нашёл reusable physical-table grouper:
riichi-проекты группируют уже известное игровое состояние, а не camera boxes.
Generic rectangles недостаточно, чтобы отличить руку, сброс и стену одинаковой
геометрии. Поэтому semantic grouping объединяет локальную scale-normalized
геометрию, face/back evidence существующего LiteRT и направление игрока из
capture protocol. Перед grouping сцена нормализуется по крайним детекциям: это
не зависит от центра кадра и не принимает обрезанный круглый стол за
четырёхугольник. Полная homography оставлена до появления разметки ключевых
точек: выдуманная перспектива на текущих фото сращивала руки и сбросы.
Микро-зоны растут от уверенных hand/wall/dora/discard seeds, а proposals вне
плоскости или spatial support попадают в `noise`. Индикатор доры отделяется
от спинок мёртвой стены; для маленькой стены доступен резервный contrast score.
Преобразование сцены рисует зоны обратно на фото, а `noise` в итоговый overlay
не выводится. Opponent hands назначаются до discard: закрытые
фрагменты срастаются вдоль края стола, открытая линейная группа рядом со стеной
имеет приоритет над discard, а wall остаётся внутренней back-only линией.
Официальный
[`Ultralytics/SAHI` guide](https://github.com/ultralytics/ultralytics/blob/main/docs/en/guides/sahi-tiled-inference.md)
полезен для sliced detection и box merging, но не определяет hand/dead
wall/discard; заменять им текущие 12 crops стоит только после benchmark.

## Отклонённые и резервные эксперименты

| Источник | Решение на наших фото |
|---|---|
| [`MLFreelib/cvflow`](https://github.com/MLFreelib/cvflow) | отдельный локальный эксперимент; визуально хуже CountGD++ |
| GroundingDINO | недостаточный охват/стабильность по полному столу |
| OWLv2 | недостаточный охват мелких и перспективных тайлов |
| RF-DETR Nano | на пяти клубных фото при IoU 0.30: P=0.366, R=0.151; отклонён |
| YOLO + crops/SAHI | резервный локальный detector; перенос хуже CountGD++ |
| top-down homography | только второй проход: помогает номиналам, но может терять proposals |

Эти варианты не удалены из истории экспериментов, но не должны незаметно
становиться production path без сравнения на одном проверенном split.
