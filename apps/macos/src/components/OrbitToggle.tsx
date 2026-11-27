import { Switch } from "@kiridey/orbit-ui";
import type { InputHTMLAttributes } from "react";
export function OrbitToggle({
  label,
  ...props
}: Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & { label: string }) {
  return (
    <Switch
      {...props}
      className="lad-toggle"
      label={<span className="orbit-sr-only">{label}</span>}
    />
  );
}
