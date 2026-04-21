import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const devApiTarget = env.VITE_DEV_API_TARGET || "http://127.0.0.1:4866";
  return {
    plugins: [react()],
    // Subdomain root deploy (smp.thecabal.app). Override with VITE_BASE_URL=/path/ if needed.
    base: env.VITE_BASE_URL || "/",
    server: {
      proxy: {
        "/api": devApiTarget,
        "/health": devApiTarget,
        "/static": devApiTarget,
      },
    },
  };
});
