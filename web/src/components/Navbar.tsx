import { useEffect, useId, useRef, useState, type MouseEvent } from "react";
import { CABAL_ORIGIN } from "../config";
import "./Navbar.css";
import SiteMenuDropdown, { type NavLink } from "./SiteMenuDropdown";

const SOLIDIFY_RANGE = 120;

const navLinks: NavLink[] = [
  { name: "Home", href: "/" },
  { name: "Deploy", href: "/deploy" },
  { name: "Profile", href: "/profile" },
  { name: "Docs", href: `${CABAL_ORIGIN}/docs` },
];

function MenuToggle({
  variant,
  isMenuOpen,
  onToggle,
  ariaControls,
}: {
  variant: "desktop" | "mobile";
  isMenuOpen: boolean;
  onToggle: (e: MouseEvent<HTMLElement>) => void;
  ariaControls?: string;
}) {
  const label = isMenuOpen ? "Close menu" : "Open menu";
  const controlsProps =
    ariaControls !== undefined ? { "aria-controls": isMenuOpen ? ariaControls : undefined } : {};
  if (variant === "desktop") {
    return (
      <button
        type="button"
        className="desktop-menu-trigger menu-trigger"
        onClick={onToggle}
        aria-label={label}
        aria-expanded={isMenuOpen}
        {...controlsProps}
      >
        <span className="desktop-hamburger-icon" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
      </button>
    );
  }
  return (
    <button
      type="button"
      className="mobile-menu-toggle"
      onClick={onToggle}
      aria-label={label}
      aria-expanded={isMenuOpen}
      {...controlsProps}
    >
      <div className={`hamburger ${isMenuOpen ? "active" : ""}`}>
        <span />
        <span />
        <span />
      </div>
    </button>
  );
}

export default function Navbar() {
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [solidify, setSolidify] = useState(0);
  const menuFocusReturnRef = useRef<HTMLElement | null>(null);
  const menuId = useId();

  useEffect(() => {
    let rafId: number | null = null;
    const flush = () => {
      rafId = null;
      const y = window.scrollY ?? document.documentElement.scrollTop ?? 0;
      const progress = Math.min(1, Math.max(0, y / SOLIDIFY_RANGE));
      setSolidify(progress);
    };
    const schedule = () => {
      if (rafId !== null) return;
      rafId = requestAnimationFrame(flush);
    };
    schedule();
    window.addEventListener("scroll", schedule, { passive: true });
    return () => {
      window.removeEventListener("scroll", schedule);
      if (rafId !== null) cancelAnimationFrame(rafId);
    };
  }, []);

  const toggleMenu = (e: MouseEvent<HTMLElement>) => {
    menuFocusReturnRef.current = e.currentTarget;
    setIsMenuOpen((p) => !p);
  };

  return (
    <nav className="navbar" style={{ ["--header-solidify" as string]: solidify }}>
      <div className="navbar-container">
        <a href={`${CABAL_ORIGIN}/`} className="navbar-logo">
          <span className="logo-image-wrap">
            <img
              src="/cabal-logo-512-v2.jpg"
              alt="Cabal Logo"
              width={32}
              height={32}
              className="logo-image"
            />
          </span>
          <span className="logo-text">CABAL</span>
        </a>

        <MenuToggle
          variant="desktop"
          isMenuOpen={isMenuOpen}
          onToggle={toggleMenu}
          ariaControls={menuId}
        />
        <MenuToggle
          variant="mobile"
          isMenuOpen={isMenuOpen}
          onToggle={toggleMenu}
          ariaControls={menuId}
        />
      </div>

      <SiteMenuDropdown
        isOpen={isMenuOpen}
        onClose={() => setIsMenuOpen(false)}
        navLinks={navLinks}
        focusReturnRef={menuFocusReturnRef}
        menuId={menuId}
      />
    </nav>
  );
}
