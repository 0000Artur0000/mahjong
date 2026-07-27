// Shared active-link class for NavLink. NavLink also sets aria-current="page",
// so the active state is conveyed to assistive tech, not by colour alone.
export function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? "nav-link nav-link--active" : "nav-link";
}
