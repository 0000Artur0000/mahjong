import { createContext, useContext } from "react";

export type Tone = "info" | "positive" | "danger";

export const ToastContext = createContext<
  (t: { title: string; tone?: Tone }) => void
>(() => {});

export function useToast() {
  return useContext(ToastContext);
}
