import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";
import { ThemeProvider } from "./theme/ThemeProvider";

const rootEl = document.getElementById("root");
if (!rootEl) {
  console.error("Root element '#root' not found — cannot mount React app.");
  throw new Error("Missing #root element");
}

createRoot(rootEl).render(
  <StrictMode>
    <ThemeProvider>
      <App />
    </ThemeProvider>
  </StrictMode>
);
