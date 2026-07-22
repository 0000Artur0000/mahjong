import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

function App() {
  return (
    <main>
      <p className="eyebrow">Dorahub foundation</p>
      <h1>Риичи без лишней магии</h1>
      <p>
        Monorepo готова к параллельной разработке Backend, Frontend, Vision и
        инфраструктуры.
      </p>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

