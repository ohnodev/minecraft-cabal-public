import { useEffect, useState, type CSSProperties } from "react";
import { useTheme } from "../theme/ThemeProvider";

interface ThemeToggleProps {
  size?: "small" | "normal" | "compact";
}

export default function ThemeToggle({ size = "normal" }: ThemeToggleProps) {
  const { isDarkTheme, toggleTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  const iconSize = size === "compact" ? 14 : size === "small" ? 16 : 20;
  const isSmall = size === "small" || size === "compact";
  const isCompact = size === "compact";

  const baseStyles: CSSProperties = {
    background: "var(--color-surface)",
    border: "1px solid var(--color-border)",
    borderRadius: "0.5rem",
    padding: isCompact ? "0.25rem" : isSmall ? "0rem" : "0.75rem",
    cursor: "pointer",
    transition: "all 0.2s ease",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    boxShadow: "var(--shadow-sm)",
    color: "var(--color-text-primary)",
  };

  const fixedStyles: CSSProperties =
    !isSmall
      ? { position: "fixed", top: "1rem", right: "1rem", zIndex: 1000 }
      : {};

  if (!mounted) {
    return (
      <button
        type="button"
        className="theme-toggle-btn"
        style={{
          ...baseStyles,
          ...(size === "normal" ? fixedStyles : {}),
        }}
        aria-hidden
      >
        <div
          style={{
            width: iconSize,
            height: iconSize,
          }}
        />
      </button>
    );
  }

  return (
    <button
      type="button"
      className="theme-toggle-btn"
      onClick={toggleTheme}
      aria-label={isDarkTheme ? "Switch to light mode" : "Switch to dark mode"}
      style={{
        ...baseStyles,
        ...(!isSmall ? fixedStyles : {}),
      }}
    >
      {isDarkTheme ? (
        <svg
          className="sun-icon"
          width={iconSize}
          height={iconSize}
          viewBox="0 0 24 24"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden
        >
          <circle cx="12" cy="12" r="5" stroke="currentColor" strokeWidth="2" />
          <path d="M12 2V4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <path d="M12 20V22" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <path d="M4 12L2 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <path d="M22 12L20 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <path
            d="M19.7778 4.22266L17.5558 6.25424"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          />
          <path
            d="M4.22217 4.22266L6.44418 6.25424"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          />
          <path
            d="M6.44434 17.5557L4.22211 19.7779"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          />
          <path
            d="M19.7778 19.7773L17.5558 17.5551"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          />
        </svg>
      ) : (
        <svg
          className="moon-icon"
          width={iconSize}
          height={iconSize}
          viewBox="0 0 24 24"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden
        >
          <path
            d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      )}
    </button>
  );
}
