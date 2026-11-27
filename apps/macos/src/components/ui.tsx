// SPDX-License-Identifier: AGPL-3.0-only
import {
  useState,
  useEffect,
  useRef,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { AudioLines, Heart, Music2, type LucideIcon } from "lucide-react";
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
      <span className="lad-button-label">{children}</span>
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

// Only a deliberate press animates; hydration and track changes stay quiet.
export function LikeButton({
  active,
  identity,
  onClick,
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  active: boolean;
  identity?: string;
}) {
  const root = useRef<HTMLButtonElement>(null);
  const intent = useRef<{ identity?: string; value: boolean } | null>(null);
  const animation = useRef<Animation | null>(null);
  useEffect(() => {
    const requested = intent.current;
    if (
      !requested ||
      requested.identity !== identity ||
      requested.value !== active
    )
      return;
    intent.current = null;
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    animation.current?.cancel();
    animation.current =
      root.current
        ?.querySelector("svg")
        ?.animate(
          active
            ? [
                { transform: "scale(1)" },
                { transform: "scale(1.2)", offset: 0.38 },
                { transform: "scale(.94)", offset: 0.7 },
                { transform: "scale(1)" },
              ]
            : [
                { transform: "translateX(0)" },
                { transform: "translateX(-3px) rotate(-7deg)", offset: 0.22 },
                { transform: "translateX(3px) rotate(7deg)", offset: 0.5 },
                { transform: "translateX(-2px)", offset: 0.75 },
                { transform: "translateX(0)" },
              ],
          { duration: active ? 340 : 280, easing: "ease-out" },
        ) || null;
  }, [active, identity]);
  useEffect(
    () => () => {
      animation.current?.cancel();
      intent.current = null;
    },
    [identity],
  );
  return (
    <OrbitButton
      ref={root}
      {...props}
      iconOnly
      variant="ghost"
      className={clsx(
        "icon-button",
        "like-button",
        active && "is-liked",
        className,
      )}
      aria-pressed={active}
      onClick={(event) => {
        intent.current = { identity, value: !active };
        onClick?.(event);
      }}
    >
      <Heart size={20} fill={active ? "currentColor" : "none"} />
    </OrbitButton>
  );
}

export function Art({
  item,
  size = "md",
  className,
}: {
  item: Partial<Pick<Track, "id" | "title" | "cover" | "art">>;
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
