import { describe, expect, it } from "vitest";
import { nextTabIndex } from "../src/ui/tabs-keys";

// Клавиатурная навигация Tabs по APG. Проверяем ту же функцию, которую
// использует компонент, поэтому изменение поведения ломает тест.
describe("nextTabIndex", () => {
  it("moves right and wraps at the end", () => {
    expect(nextTabIndex("ArrowRight", 0, 3)).toBe(1);
    expect(nextTabIndex("ArrowRight", 2, 3)).toBe(0);
  });

  it("moves left and wraps at the start", () => {
    expect(nextTabIndex("ArrowLeft", 2, 3)).toBe(1);
    expect(nextTabIndex("ArrowLeft", 0, 3)).toBe(2);
  });

  it("jumps to the first and last tab", () => {
    expect(nextTabIndex("Home", 2, 3)).toBe(0);
    expect(nextTabIndex("End", 0, 3)).toBe(2);
  });

  it("ignores other keys so typing is not swallowed", () => {
    for (const key of ["Tab", "Enter", " ", "a", "ArrowUp", "Escape"]) {
      expect(nextTabIndex(key, 1, 3)).toBeNull();
    }
  });

  it("stays on the only tab", () => {
    for (const key of ["ArrowRight", "ArrowLeft", "Home", "End"]) {
      expect(nextTabIndex(key, 0, 1)).toBe(0);
    }
  });
});
