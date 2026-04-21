import ThemeToggle from "./ThemeToggle";
import "./Footer.css";

export default function Footer() {
  const MailIcon = () => (
    <svg
      width="14px"
      height="14px"
      viewBox="0 0 24 24"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
      <polyline points="22,6 12,13 2,6" />
    </svg>
  );

  const XIcon = () => (
    <svg width="14px" height="14px" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor" aria-hidden>
      <path d="M12.2752 1.28064H14.555L9.57443 6.97311L15.4337 14.7193H10.8459L7.25264 10.0213L3.14111 14.7193H0.859989L6.1872 8.63054L0.566406 1.28064H5.27062L8.51863 5.5748L12.2752 1.28064ZM11.4751 13.3547H12.7384L4.58421 2.57351H3.22863L11.4751 13.3547Z" />
    </svg>
  );

  const TelegramIcon = () => (
    <svg width="14px" height="14px" viewBox="0 0 12 12" xmlns="http://www.w3.org/2000/svg" fill="currentColor" aria-hidden>
      <g clipPath="url(#smp-tg-clip)">
        <path d="M10.6644 0.844343L0.447706 5.09657C0.447706 5.09657 -0.0353248 5.27215 0.00206369 5.59599C0.0404588 5.92041 0.43462 6.06881 0.43462 6.06881L3.00537 6.98655C3.00537 6.98655 3.78132 9.686 3.93404 10.1997C4.08676 10.7119 4.20928 10.724 4.20928 10.724C4.3515 10.7895 4.48077 10.6851 4.48077 10.6851L6.14169 9.07902L8.73026 11.1846C9.43044 11.5085 9.68525 10.8335 9.68525 10.8335L11.5042 1.10132C11.5042 0.453058 10.6644 0.844343 10.6644 0.844343ZM8.85566 10.0508L6.08632 7.79889L5.22696 8.62964L5.41592 6.86217L9.10731 3.36361L4.07554 6.35095L1.84359 5.55429L10.3591 2.00986L8.85566 10.0508Z" />
      </g>
      <defs>
        <clipPath id="smp-tg-clip">
          <rect width="12" height="12" />
        </clipPath>
      </defs>
    </svg>
  );

  const YouTubeIcon = () => (
    <svg width="14px" height="14px" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="currentColor" aria-hidden>
      <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z" />
    </svg>
  );

  const DiscordIcon = () => (
    <svg width="14px" height="14px" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" fill="currentColor" aria-hidden>
      <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028 14.09 14.09 0 0 0 1.226-1.994.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.419 0 1.334-.956 2.419-2.157 2.419zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.419 0 1.334-.946 2.419-2.157 2.419z" />
    </svg>
  );

  return (
    <footer className="footer">
      <div className="footer-container">
        <div className="footer-content">
          <p className="footer-text">
            © {new Date().getFullYear()} The Cabal<span className="footer-tagline">. Built for the future.</span>
          </p>
          <p className="footer-email footer-email-text">contact@thecabal.app</p>
          <a href="mailto:contact@thecabal.app" className="footer-email-icon" title="Email us">
            <MailIcon />
          </a>
        </div>
        <div className="footer-right">
          <div className="footer-social">
            <a
              href="https://discord.gg/2NR3W7j4vP"
              target="_blank"
              rel="noopener noreferrer"
              className="footer-social-link"
              aria-label="Join Cabal SMP on Discord"
              title="Discord"
            >
              <DiscordIcon />
            </a>
            <a
              href="https://x.com/TheBasedCabal"
              target="_blank"
              rel="noopener noreferrer"
              className="footer-social-link"
              aria-label="Follow us on X"
              title="Follow us on X"
            >
              <XIcon />
            </a>
            <a
              href="https://t.me/CryptoCabalPortal"
              target="_blank"
              rel="noopener noreferrer"
              className="footer-social-link"
              aria-label="Join our Telegram"
              title="Join our Telegram"
            >
              <TelegramIcon />
            </a>
            <a
              href="https://www.youtube.com/@TheBasedCabal"
              target="_blank"
              rel="noopener noreferrer"
              className="footer-social-link"
              aria-label="Subscribe on YouTube"
              title="Subscribe on YouTube"
            >
              <YouTubeIcon />
            </a>
          </div>
          <ThemeToggle size="small" />
        </div>
      </div>
    </footer>
  );
}
