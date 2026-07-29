# ADR-0002: Гибридный анимационный стек (три слоя)

Дата: 2026-07-29 · Статус: принято · Поток: RD-03

## Контекст

`plans/frontend.md` изначально предписывал «CSS Modules + custom properties +
нативные переходы, без тяжёлых UI-фреймворков». Редизайн «Noir Gold» требует
сцен, которые чистым CSS описывать дорого и хрупко: spring-физика раздачи
тайлов, drag в редакторе руки, параллакс, count-up очков, общие элементы
между экранами.

При этом жёсткие бюджеты не отменяются: LCP < 2,5 с и INP < 200 мс на среднем
телефоне (FE-33), `prefers-reduced-motion` — обязательный kill-switch,
main-чанк должен оставаться минимальным.

## Решение

Три слоя анимации с едиными токенами (`--duration-*`, `--ease-*`,
`--stagger-step` в tokens.css):

1. **CSS (основной)** — hover/press/focus микровзаимодействия, reveal-каскады,
   ключевые эффекты (лампа, фольга, зерно). Ноль зависимостей.
2. **View Transitions API + WAAPI** — переходы роутов, theme-wipe из точки
   клика, shared-element сцены. Progressive enhancement: без API переход
   просто мгновенный.
3. **`motion` (framer-motion), только lazy** — spring-хореографии, drag,
   AnimatePresence. Разрешён исключительно в lazy-роутах (`src/demo/**`,
   `src/showcase/**`) и `src/motion/**`; все импорты идут через
   `src/motion/motion.ts`. Guard: `scripts/motion-check.mjs` падает в CI при
   импорте библиотеки из общего кода — main-чанк гарантированно чист.

Утилиты без зависимостей (`src/motion/`): `Reveal` (IntersectionObserver),
`CountUp` (rAF, tabular-nums), `Magnetic` (pointer:fine only),
`withViewTransition`/`themeWipe`.

## Последствия

- `plans/frontend.md` обновлён (раздел RD): формулировка «только нативные
  переходы» заменяется на три слоя выше.
- Все слои обязаны уважать `prefers-reduced-motion`: CSS — глобальным
  kill-switch, motion — через `useReducedMotion`, VT — проверкой в хелпере.
- Бюджет: main JS не должен расти от слоя 3; рост допустим только в чанках
  lazy-роутов (`/demo/*`, `/styleguide`).
- Альтернатива «всё на CSS» отклонена: spring-физика и drag вручную —
  неподдерживаемый объём кода. Альтернатива «motion везде» отклонена из-за
  ~35–50 KB gzip в критическом пути.
