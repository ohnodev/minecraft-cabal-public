/**
 * PM2 ecosystem for cabal-smp-api — single source of truth.
 * Resolve paths from repo root.
 */
const path = require("path");
const root = path.resolve(__dirname);

const DEFAULT_CORS =
  "http://localhost:5173,http://localhost:4173,https://smp.thecabal.app,http://smp.thecabal.app";

module.exports = {
  apps: [
    {
      name: "cabal-smp-api",
      cwd: path.join(root, "api"),
      script: process.env.PM2_NODE_PATH || process.execPath,
      args: "dist/index.js",
      instances: 1,
      autorestart: true,
      watch: false,
      max_memory_restart: "512M",
      out_file: path.join(root, "logs", "cabal-smp-api-out.log"),
      error_file: path.join(root, "logs", "cabal-smp-api-error.log"),
      combine_logs: false,
      log_file: null,
      log_date_format: "YYYY-MM-DD HH:mm:ss Z",
      max_restarts: 10,
      min_uptime: "10s",
      env: {
        API_HOST: process.env.API_HOST || "127.0.0.1",
        API_PORT: process.env.API_PORT || "4866",
        MC_HOST: process.env.MC_HOST || "127.0.0.1",
        MC_PORT: process.env.MC_PORT || "25565",
        MC_SERVER_DIR: process.env.MC_SERVER_DIR || path.join(root, "server"),
        CORS_ORIGINS: process.env.CORS_ORIGINS || DEFAULT_CORS,
      },
    },
  ],
};
