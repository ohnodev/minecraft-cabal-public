import { memo } from "react";

interface IconProps {
  size?: number;
  className?: string;
  color?: string;
}

export const SearchIcon = memo(function SearchIcon({
  size = 16,
  className = "",
  color = "currentColor",
}: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className={className}
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden
    >
      <circle cx="11" cy="11" r="8" stroke={color} strokeWidth="2" />
      <path
        d="M21 21L16.65 16.65"
        stroke={color}
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
});
