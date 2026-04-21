const raw = (import.meta.env.VITE_CABAL_ORIGIN as string | undefined)?.trim() ?? "";
const stripped = raw.replace(/\/$/, "");
export const CABAL_ORIGIN = stripped || "https://thecabal.app";
