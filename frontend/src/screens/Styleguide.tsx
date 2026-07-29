import { useEffect, useState, type ReactNode } from "react";
import { config } from "@/config";
import { ApiDemo } from "@/showcase/ApiDemo";
import { Primitives } from "@/showcase/Primitives";
import { Tile, TILE_IDS } from "@/ui";
import "../styles/showcase.css";

function useCountdown(from: number): number {
  const [s, setS] = useState(from);
  useEffect(() => {
    const id = setInterval(() => setS((v) => (v > 0 ? v - 1 : from)), 1000);
    return () => clearInterval(id);
  }, [from]);
  return s;
}

const WINDS = [
  { glyph: "東", label: "восток", dealer: true },
  { glyph: "南", label: "юг", dealer: false },
  { glyph: "西", label: "запад", dealer: false },
  { glyph: "北", label: "север", dealer: false },
] as const;

const DRAGONS = [
  { glyph: "中", label: "чун", tone: "danger" },
  { glyph: "發", label: "хацу", tone: "positive" },
  { glyph: "白", label: "хаку", tone: "board" },
] as const;

const SWATCHES = [
  { token: "--color-bg", label: "bg", on: "--color-fg" },
  { token: "--color-surface", label: "surface", on: "--color-fg" },
  {
    token: "--color-surface-raised",
    label: "surface-raised",
    on: "--color-fg",
  },
  { token: "--color-accent", label: "accent", on: "--color-on-accent" },
  { token: "--color-positive", label: "positive", on: "--color-on-positive" },
  { token: "--color-danger", label: "danger", on: "--color-on-danger" },
  { token: "--color-warning", label: "warning", on: "--color-on-warning" },
] as const;

const RAMPS: Record<string, string[]> = {
  gold: ["--gold-300", "--gold-400", "--gold-500", "--gold-600", "--gold-700"],
  jade: ["--jade-300", "--jade-400", "--jade-500", "--jade-600", "--jade-700"],
  coral: [
    "--coral-300",
    "--coral-400",
    "--coral-500",
    "--coral-600",
    "--coral-700",
  ],
  ink: ["--ink-100", "--ink-300", "--ink-500", "--ink-700", "--ink-850"],
};

const TYPE_SCALE = [
  ["--text-4xl", "4xl"],
  ["--text-3xl", "3xl"],
  ["--text-2xl", "2xl"],
  ["--text-xl", "xl"],
  ["--text-lg", "lg"],
  ["--text-md", "md"],
  ["--text-sm", "sm"],
  ["--text-xs", "xs"],
] as const;

const SPACE = [
  "--space-1",
  "--space-2",
  "--space-3",
  "--space-4",
  "--space-5",
  "--space-6",
  "--space-7",
  "--space-8",
];
const RADII = [
  "--radius-xs",
  "--radius-sm",
  "--radius-md",
  "--radius-lg",
  "--radius-xl",
];
const SHADOWS = ["--shadow-1", "--shadow-2", "--shadow-3"];

const SCORES = [
  { wind: "東", name: "дилер", score: 32100 },
  { wind: "南", name: "юг", score: 25200 },
  { wind: "西", name: "запад", score: 24700 },
  { wind: "北", name: "север", score: 18000 },
];

function Plate({
  label,
  title,
  children,
}: {
  label: string;
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="plate">
      <header className="plate__head">
        <p className="eyebrow">{label}</p>
        <h2 className="plate__title">{title}</h2>
      </header>
      {children}
    </section>
  );
}

// Named `Component` so it can be loaded as a lazy route module.
export function Component() {
  const seconds = useCountdown(60);
  const timer = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

  return (
    <div className="showcase">
      <div className="hero">
        <p className="eyebrow">Дизайн-система · mahjong noir</p>
        <h1 className="hero__title">
          Одна палитра
          <br />
          для всего стола
        </h1>
        <p className="lede">
          Токены цвета, типографики, отступов, радиусов, теней и слоёв — единый
          источник без локальных magic values. Обе темы проверены на контраст
          WCAG AA и переживают forced-colors и reduced-motion.
        </p>

        <div
          className="specimen"
          role="img"
          aria-label="Ветра востока, юга, запада, севера и драконы чун, хацу, хаку"
        >
          {WINDS.map((w) => (
            <span
              key={w.glyph}
              className={w.dealer ? "tile tile--dealer" : "tile"}
            >
              <span className="tile__glyph" aria-hidden="true">
                {w.glyph}
              </span>
              <span className="tile__label">
                {w.dealer ? "дилер" : w.label}
              </span>
            </span>
          ))}
          <span className="specimen__gap" aria-hidden="true" />
          {DRAGONS.map((d) => (
            <span key={d.glyph} className={`tile tile--${d.tone}`}>
              <span className="tile__glyph" aria-hidden="true">
                {d.glyph}
              </span>
              <span className="tile__label">{d.label}</span>
            </span>
          ))}
        </div>
      </div>

      <Plate label="Tiles" title="Тайлсет · Noir Gold">
        <p className="lede">
          Спрайт <code>public/tiles.svg</code>: 34 лица, ака-пятёрки, рубашка
          и пустой тайл. Нотация mjai; у каждого тайла есть текстовое имя для
          screen reader.
        </p>
        <div className="tileset">
          {TILE_IDS.map((id) => (
            <figure key={id} className="tileset__item">
              <Tile tile={id} width={54} />
              <figcaption className="tileset__label">{id}</figcaption>
            </figure>
          ))}
        </div>
      </Plate>

      <Plate label="Color" title="Семантические токены">
        <div className="swatches">
          {SWATCHES.map((s) => (
            <div
              key={s.token}
              className="swatch"
              style={{ background: `var(${s.token})`, color: `var(${s.on})` }}
            >
              <span className="swatch__name">{s.label}</span>
              <code className="swatch__var">{s.token}</code>
            </div>
          ))}
        </div>
        <div className="ramps">
          {Object.entries(RAMPS).map(([name, steps]) => (
            <div key={name} className="ramp">
              <span className="ramp__name">{name}</span>
              <div className="ramp__strip">
                {steps.map((step) => (
                  <span
                    key={step}
                    className="ramp__step"
                    style={{ background: `var(${step})` }}
                    title={step}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      </Plate>

      <Plate label="Type" title="Onest · шкала и числа">
        <div className="type-scale">
          {TYPE_SCALE.map(([token, name]) => (
            <div key={token} className="type-row">
              <span className="type-row__name">{name}</span>
              <span
                className="type-row__sample"
                style={{ fontSize: `var(${token})` }}
              >
                Риичи без магии
              </span>
            </div>
          ))}
        </div>

        <div className="numerals card">
          <div className="scoreboard">
            {SCORES.map((row) => (
              <div key={row.wind} className="scoreboard__row">
                <span className="scoreboard__wind" aria-hidden="true">
                  {row.wind}
                </span>
                <span className="scoreboard__name">{row.name}</span>
                <span className="scoreboard__score tabular">
                  {row.score.toLocaleString("ru-RU")}
                </span>
              </div>
            ))}
          </div>
          <div className="timer">
            <span className="eyebrow">Ход</span>
            <span className="timer__value tabular">{timer}</span>
          </div>
        </div>
      </Plate>

      <Plate label="Space" title="Сетка 4px">
        <div className="metric-list">
          {SPACE.map((token) => (
            <div key={token} className="metric-row">
              <code className="metric-row__name">{token}</code>
              <span
                className="metric-row__bar"
                style={{ width: `var(${token})` }}
              />
            </div>
          ))}
        </div>
      </Plate>

      <Plate label="Radius & elevation" title="Радиусы и тени">
        <div className="radii">
          {RADII.map((token) => (
            <div
              key={token}
              className="radius-chip"
              style={{ borderRadius: `var(${token})` }}
            >
              <code>{token.replace("--radius-", "")}</code>
            </div>
          ))}
        </div>
        <div className="cards">
          {SHADOWS.map((token) => (
            <div
              key={token}
              className="card elevated"
              style={{ boxShadow: `var(${token})` }}
            >
              <code>{token.replace("--", "")}</code>
            </div>
          ))}
        </div>
      </Plate>

      <Plate label="API" title="Клиент и mock-сервер">
        <ApiDemo />
      </Plate>

      <Primitives />

      <footer className="foot">
        <code className="tabular">{config.appName}</code>
        <code className="tabular">v{config.version}</code>
        <code className="tabular">{config.mode}</code>
        <code>{config.apiBaseUrl || "same-origin"}</code>
      </footer>
    </div>
  );
}
