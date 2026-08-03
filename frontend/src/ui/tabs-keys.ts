/**
 * Куда уходит фокус по клавише (APG: стрелки замыкаются по кругу, Home/End —
 * к краям). Возвращает null для клавиш, которые табам не принадлежат, чтобы
 * ввод не проглатывался. Живёт отдельным модулем: react-refresh требует, чтобы файл компонента
 * экспортировал только компоненты.
 */
export function nextTabIndex(
  key: string,
  active: number,
  count: number,
): number | null {
  const last = count - 1;
  return key === "ArrowRight"
    ? (active + 1) % count
    : key === "ArrowLeft"
      ? (active - 1 + count) % count
      : key === "Home"
        ? 0
        : key === "End"
          ? last
          : null;
}
