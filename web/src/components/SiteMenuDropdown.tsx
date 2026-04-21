import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { createPortal } from "react-dom";
import { CABAL_ORIGIN } from "../config";
import ThemeToggle from "./ThemeToggle";
import ExternalLinkIcon from "../icons/ExternalLinkIcon";
import { CabalIcon } from "../icons/CabalIcon";
import { SearchIcon } from "../icons/SearchIcon";

export type NavLink = { name: string; href: string };

function ModalLinkIcon({ name }: { name: string }) {
  const n = name.toLowerCase();
  if (n === "home") {
    return (
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M3 10.5L12 3L21 10.5"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M5.5 9.5V20H18.5V9.5"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }
  if (n === "deploy" || n === "launch") {
    return <CabalIcon size={12} color="currentColor" withGlow={false} />;
  }
  if (n === "profile") {
    return (
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="8" r="3.2" stroke="currentColor" strokeWidth="1.8" />
        <path
          d="M5 19C5.95 16.25 8.6 14.5 12 14.5C15.4 14.5 18.05 16.25 19 19"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </svg>
    );
  }
  if (n === "docs") {
    return (
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M7 4H14L18 8V20H7V4Z"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
        <path d="M14 4V8H18" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      </svg>
    );
  }
  return null;
}

function SocialsIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.8" />
      <path d="M3.5 12H20.5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path
        d="M12 3C14.5 5.4 15.9 8.57 15.9 12C15.9 15.43 14.5 18.6 12 21"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
      <path
        d="M12 3C9.5 5.4 8.1 8.57 8.1 12C8.1 15.43 9.5 18.6 12 21"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
    </svg>
  );
}

function toAbsolute(href: string): string {
  if (href.startsWith("http")) return href;
  return `${CABAL_ORIGIN}${href.startsWith("/") ? href : `/${href}`}`;
}

/** True when resolved URL is http(s) and targets a different origin than the Cabal site. */
function isCrossOriginLink(resolvedUrl: string): boolean {
  try {
    const u = new URL(resolvedUrl);
    if (u.protocol !== "http:" && u.protocol !== "https:") return false;
    const cabalOrigin = new URL(CABAL_ORIGIN).origin;
    return u.origin !== cabalOrigin;
  } catch {
    return false;
  }
}

const DISCORD_HREF = "https://discord.gg/2NR3W7j4vP";

const SOCIAL_LINKS = [
  { name: "Discord", href: DISCORD_HREF },
  { name: "X", href: "https://x.com/TheBasedCabal" },
  { name: "Telegram", href: "https://t.me/CryptoCabalPortal" },
  { name: "YouTube", href: "https://www.youtube.com/@TheBasedCabal" },
];

function listFocusables(container: HTMLElement): HTMLElement[] {
  const sel =
    "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex=\"-1\"])";
  return Array.from(container.querySelectorAll<HTMLElement>(sel)).filter((el) => !el.hasAttribute("disabled"));
}

interface SiteMenuDropdownProps {
  isOpen: boolean;
  onClose: () => void;
  navLinks: NavLink[];
  focusReturnRef: MutableRefObject<HTMLElement | null>;
  menuId: string;
}

export default function SiteMenuDropdown({
  isOpen,
  onClose,
  navLinks,
  focusReturnRef,
  menuId,
}: SiteMenuDropdownProps) {
  const [socialsOpen, setSocialsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) setSocialsOpen(false);
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const drop = dropdownRef.current;
    if (!drop) return;

    let raf = 0;
    const focusFirst = () => {
      const list = listFocusables(drop);
      list[0]?.focus();
    };
    raf = requestAnimationFrame(focusFirst);

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const list = listFocusables(drop);
      if (list.length === 0) return;
      const activeEl = document.activeElement;
      const active = activeEl instanceof HTMLElement ? activeEl : null;
      const i = active !== null ? list.indexOf(active) : -1;
      if (e.shiftKey) {
        if (i <= 0) {
          e.preventDefault();
          list[list.length - 1]?.focus();
        }
      } else if (i === -1 || i >= list.length - 1) {
        e.preventDefault();
        list[0]?.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("keydown", onKeyDown);
      focusReturnRef.current?.focus();
    };
  }, [isOpen, onClose, focusReturnRef]);

  if (!isOpen) return null;

  return createPortal(
    <>
      <div className="header-dropdown-backdrop" onClick={onClose} aria-hidden="true" />
      <div className="header-dropdown" id={menuId} ref={dropdownRef}>
        <a href={`${CABAL_ORIGIN}/search`} className="header-dropdown-item" onClick={onClose}>
          <SearchIcon size={12} color="currentColor" />
          <span>Search</span>
        </a>

        <div className="header-dropdown-divider" />

        {navLinks.map((link) => {
          const url = toAbsolute(link.href);
          const external = isCrossOriginLink(url);
          return external ? (
            <a
              key={link.name}
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="header-dropdown-item"
              onClick={onClose}
            >
              <ModalLinkIcon name={link.name} />
              <span>{link.name}</span>
              <ExternalLinkIcon size={12} />
            </a>
          ) : (
            <a key={link.name} href={url} className="header-dropdown-item" onClick={onClose}>
              <ModalLinkIcon name={link.name} />
              <span>{link.name}</span>
            </a>
          );
        })}

        <button
          type="button"
          className="header-dropdown-item header-dropdown-toggle"
          onClick={() => setSocialsOpen((p) => !p)}
          aria-expanded={socialsOpen}
        >
          <SocialsIcon />
          <span>Socials</span>
          <span className={`header-dropdown-arrow ${socialsOpen ? "open" : ""}`}>▼</span>
        </button>
        {socialsOpen && (
          <div className="header-dropdown-sub">
            {SOCIAL_LINKS.map((link) => (
              <a
                key={link.name}
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                className="header-dropdown-item header-dropdown-subitem"
                onClick={onClose}
              >
                <span>{link.name}</span>
                <ExternalLinkIcon size={12} />
              </a>
            ))}
          </div>
        )}

        <div className="header-dropdown-item header-dropdown-theme">
          <span>Theme</span>
          <ThemeToggle size="compact" />
        </div>
      </div>
    </>,
    document.body
  );
}
