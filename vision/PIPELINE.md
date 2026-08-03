# Пайплайн распознавания стола

Краткий статус и метрики находятся в [README.md](README.md). Здесь — только
воспроизводимый запуск. Все команды выполняются из `project/`; модели и фото
хранятся вне Git.

## 1. Поиск всех тайлов

Основной локальный детектор — CountGD++ из `../experiments/countgdpp` с prompt и
примерами тайлов. Подготовленное окружение запускается так:

```bash
../experiments/countgdpp/run-mahjong.sh \
  ../test/experiments/countgdpp-dataset257
```

Wrapper ожидает входные фото и exemplar/prompts внутри каталога эксперимента,
веса `checkpoints/countgd_plusplus.pth` и локальный BERT checkpoint. Его выход
`results.json` содержит box, score и исходные polygon proposals. Рекомендуемый
порог текущего эксперимента — `0.20`.

Старый single-class YOLO-пакет
`test/dorahub-tile-detector-v8-test-only.tar.gz` остаётся альтернативой. Он
приватный из-за неподтверждённой лицензии части train pool и не публикуется.

## 2. Разделение слипшихся рядов

CountGD++ иногда возвращает один box на длинный ряд. Post-process ищет белые
контуры и периодические швы, после чего сохраняет один перспективный polygon на
тайл:

```bash
python3 vision/scripts/split_tile_rows.py \
  ../test/experiments/countgdpp-dataset257/results.json \
  ../test/dataset \
  ../test/experiments/countgdpp-dataset257/results-row-split-v3.json
```

Скрипт меняет только длинные светлые ряды, подтверждённые несколькими
детекциями и швами. Его можно повторять от исходного `results.json`; повторное
обучение не требуется.

## 3. Контуры SAM2

SAM2 получает box каждого кандидата и возвращает contour mask. Маска проходит
санитарную проверку относительно исходного prompt: слишком маленькая, большая
или съехавшая заменяется CountGD++ polygon. SAM2 не ищет новые тайлы и не должен
использоваться для оценки detection recall.

Канонический локальный файл для 257 кадров:

```text
../test/experiments/sam2-countgd-row-split-v2/masks.json
```

В нём нет 40 новых кандидатов последнего `row-split-v3`; для них корректно
работает fallback на polygon. Новый полный SAM2-прогон нужен только если качество
этих 40 контуров визуально недостаточно.

## 4. Номиналы LiteRT — опционально

Модель `mahjong_yolo.tflite` распознаёт 37 риичи-классов. Для сцены обязателен
`--faces`, который использует перекрывающиеся crops:

```bash
python3 -m pip install -r vision/requirements-litert.txt
PYTHONPATH=vision/src:. python3 vision/scripts/riichi_litert.py \
  ../test/mahjong-calculator-v1.0.0/assets/mahjong_yolo.tflite \
  --faces --confidence 0.1 /path/to/photos/* > /tmp/faces.jsonl
```

Без `--faces` на полном кадре обычно остаётся 2–5 лиц вместо десятков. Для
группировки без номиналов передаётся `-`; это текущий основной режим.

## 5. Роли, sceneTiles и overlay

```bash
PYTHONPATH=vision/src:. python3 vision/scripts/layout_preview.py \
  ../test/experiments/countgdpp-dataset257/results-row-split-v3.json \
  - ../test/dataset \
  ../test/experiments/countgdpp-layout-row-split-masks-v4 \
  --masks ../test/experiments/sam2-countgd-row-split-v2/masks.json
```

Результат:

- по одному JPG overlay на входной кадр;
- `groups.json` с группами, открытыми связками и `sceneTiles`;
- по одному перспективному цветному контуру на тайл, без прямоугольников групп.

Роли: `hand`, `opponent_hand`, `discard`, `wall`, `dead_wall`, `dora`, `other`,
`noise`. `sceneTiles` хранит позицию `[x,y,z]`, `yaw`, роль, место игрока,
группу, номинал и исходный box.

Цвета overlay:

| Роль | Цвет |
|---|---|
| `hand` | зелёный |
| `opponent_hand` | жёлто-зелёный |
| `discard` | синий |
| `wall` | оранжевый |
| `dead_wall` | розовый |
| `dora` | magenta |
| `other` / `noise` | серый |

Проверять нужно и overlay, и JSON. Геометрические ограничения:

1. Не больше четырёх рук, стен и пулов сброса.
2. В обычном ряду сброса не больше шести тайлов.
3. `dead_wall` — до 14 тайлов; `dora` — 1–5 индикаторов внутри неё.
4. `chi`/`pon` — три тайла, `kan` — четыре, один вызванный тайл повёрнут.
5. `other` означает честную неопределённость; уверенная неверная роль хуже.

## 6. Оценка ролей

Создать черновик truth:

```bash
PYTHONPATH=vision/src:. python3 vision/scripts/score_layout.py seed \
  ../test/<run>/groups.json ../test/roles-truth
```

Каждый кадр надо проверить вручную, исправить `role`/`seat` и поставить
`"reviewed": true`. Непроверенные кадры не участвуют в метрике.

```bash
sed -e 's/\.jfif\.jpg"/.jfif"/g' -e 's/\.jpg\.jpg"/.jpg"/g' \
  ../test/experiments/countgdpp-old11-layout-v2-clean/groups.json | \
PYTHONPATH=vision/src:. python3 vision/scripts/score_layout.py score \
  /dev/stdin ../test/roles-truth
```

Главные показатели: precision/recall по роли, `falseRole`, `silence` и
`missedTiles`. `sed` убирает двойное расширение в именах CountGD++ только в
потоке. Актуальные значения приведены в [README.md](README.md).

## 7. Top-down как второй проход

```bash
PYTHONPATH=vision/src:. python3 vision/scripts/table_plane.py photo.jpg \
  --rectify-output /tmp/topdown
```

Выпрямление может помочь nominal classifier, но способно потерять мелкие тайлы.
Поэтому оно не заменяет исходный кадр: результаты второго прохода надо вернуть
в исходные координаты и объединить с первым.

## 8. Проверка кода

```bash
PYTHONPATH=vision/src:. python3 -m unittest discover -s vision/tests -v
python3 -m json.tool contracts/vision/vision-result.schema.json >/dev/null
```

Юнит-тесты работают без моделей, GPU и `.mlvenv`.
