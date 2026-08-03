# Ассеты и лицензии

Этот файл фиксирует происхождение и лицензии сторонних и брендовых ассетов.
Он не задаёт лицензию исходного кода: корневого `LICENSE` в репозитории пока
нет. Логотип, маскот и тайл-арт публикуются отдельно от кода.

## Тайлсет (`public/tiles.svg`, `public/favicon.svg`, `public/icons/*.png`)

- Основа: [FluffyStuff/riichi-mahjong-tiles](https://github.com/FluffyStuff/riichi-mahjong-tiles),
  pinned commit `26e127ba2117f45cdce5ea0225748cc0cfad3169`.
- Лицензия оригинала: **public domain / CC0** (см. LICENSE.md апстрима).
- Модификации Dorahub: рестайл в палитру «mahjong noir», SVGO-оптимизация,
  сборка в `<symbol>`-спрайт. Рубашка тайла (`tile-back`) — оригинальный
  дизайн Dorahub, не из апстрима.
- Права на модификации: © Dorahub, отдельная лицензия бренд-ассетов.
- Сборка: `npm run assets:tiles` (`scripts/tiles/build.mjs`).

## Иконки интерфейса

- [Lucide](https://lucide.dev) — лицензия **ISC**.
- Поставляется npm-пакетом `lucide-react`, в бандл попадают только
  используемые иконки (tree-shaking).

## Шрифты (`public/fonts/*.woff2`)

- **Noto Serif** (subset: кириллица, латиница, цифры, пунктуация; Bold 700) —
  **SIL OFL 1.1**, © The Noto Project Authors.
- **Noto Sans JP** (subset: глифы 東南西北中發白萬； Medium 500) —
  **SIL OFL 1.1**, © The Noto Project Authors.
- Subset-сборка: `npm run assets:fonts` (`scripts/fonts/build.mjs`).
- **Onest Variable** — **SIL OFL 1.1**, поставляется пакетом
  `@fontsource-variable/onest`.

## Текстуры и эффекты

Генерируются кодом (SVG-фильтры/градиенты в `src/styles/`), сторонних
растровых файлов нет.
