// SPDX-License-Identifier: AGPL-3.0-only
import { useState, type ButtonHTMLAttributes, type ReactNode } from "react";
import { AudioLines, Music2, type LucideIcon } from "lucide-react";
import clsx from "clsx";
import {
  Button as OrbitButton,
  EmptyState,
  SkeletonList,
} from "@kiridey/orbit-ui";
import type { Track, Card } from "../types";
export function Button({
  icon: Icon,
  children,
  variant = "primary",
  loading = false,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  icon?: LucideIcon;
  children: ReactNode;
  variant?: "primary" | "tonal" | "secondary" | "ghost" | "danger";
  loading?: boolean;
}) {
  return (
    <OrbitButton
      {...props}
      className={clsx("lad-button", className)}
      variant={variant}
      loading={loading}
      leading={Icon && <Icon size={20} />}
    >
      {children}
    </OrbitButton>
  );
}
export function IconButton({
  icon: Icon,
  label,
  active,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: LucideIcon;
  label: string;
  active?: boolean;
}) {
  return (
    <OrbitButton
      {...props}
      iconOnly
      variant={active ? "tonal" : "ghost"}
      aria-label={label}
      title={label}
      aria-pressed={active === undefined ? undefined : active}
      className={clsx("icon-button", active && "icon-active", className)}
    >
      <Icon size={20} />
    </OrbitButton>
  );
}

export function Art({
  item,
  size = "md",
  className,
}: {
  item: Partial<Track & Card>;
  size?: "xs" | "sm" | "md" | "lg";
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  return (
    <div
      className={clsx("art", `art-${size}`, className)}
      data-art={
        item.art ??
        Math.abs(
          String(item.id || "")
            .split("")
            .reduce((n, c) => n + c.charCodeAt(0), 0),
        ) % 6
      }
    >
      {item.cover && !failed ? (
        <img
          src={item.cover}
          alt={`Обложка: ${item.title || "трек"}`}
          onError={() => setFailed(true)}
          loading="lazy"
        />
      ) : (
        <AudioLines className="art-mark" strokeWidth={1.5} />
      )}
    </div>
  );
}
export function Empty({
  icon: Icon = Music2,
  title,
  children,
  action,
}: {
  icon?: LucideIcon;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <EmptyState
        title={title}
        description={children}
        icon={<Icon size={28} />}
        action={action}
      />
    </div>
  );
}
export function Skeleton() {
  return <SkeletonList rows={4} label="Загрузка" />;
}

export function duration(value: number) {
  if (!Number.isFinite(value)) return "0:00";
  return `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}`;
}
