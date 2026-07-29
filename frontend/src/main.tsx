import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router";
import "@fontsource-variable/onest";
import { ToastProvider } from "@/ui";
import { router } from "@/router";
import "./styles/tokens.css";
import "./styles/fonts.css";
import "./styles.css";
import "./styles/ui.css";
import "./styles/effects.css";
import "./styles/layout.css";

// In dev the app runs entirely on mocks, so it works without the backend.
// The worker (and its chunk) is only loaded in dev — never in the production build.
async function enableMocks() {
  if (!import.meta.env.DEV) return;
  const { worker } = await import("@/mocks/browser");
  await worker.start({ onUnhandledRequest: "bypass" });
}

enableMocks().then(() => {
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </StrictMode>,
  );
});
