import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";

// Dev-only Service Worker (started from main.tsx behind import.meta.env.DEV).
export const worker = setupWorker(...handlers);
