// DEV auth stub. Real email / social login is FE-07; this exists only so FE-04
// can demonstrate guarded routes, redirects and forbidden states before the
// backend. Loaders read it synchronously, so guards resolve before render.
export type Role = "user" | "admin";
export type Session = { role: Role } | null;

const KEY = "dorahub-session";

export function getSession(): Session {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed?.role === "user" || parsed?.role === "admin" ? parsed : null;
  } catch {
    return null;
  }
}

export function signIn(role: Role) {
  localStorage.setItem(KEY, JSON.stringify({ role }));
}

export function signOut() {
  localStorage.removeItem(KEY);
}
