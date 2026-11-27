// SPDX-License-Identifier: AGPL-3.0-only
import { useState } from "react";

const preference = "lad:interface-design";
export function isClassicDesign() {
  if (window.lad) return window.lad.interfaceDesign === "classic";
  return localStorage.getItem(preference) === "classic";
}

export function InterfaceDesignSetting() {
  const classic = isClassicDesign();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function switchDesign() {
    const design = classic ? "orbit" : "classic";
    setPending(true);
    setError("");
    try {
      if (window.lad?.changeInterfaceDesign) {
        await window.lad.changeInterfaceDesign(design);
      } else {
        localStorage.setItem(preference, design);
        location.reload();
      }
    } catch {
      setError("Не удалось сменить дизайн. Попробуй ещё раз.");
      setPending(false);
    }
  }
  return (
    <section className="settings-section" aria-label="Версия интерфейса">
      <h2>Версия интерфейса</h2>
      <div className="setting-row">
        <div>
          <label htmlFor="classic-design">Вернуться к старому дизайну</label>
          <p>Лад перезапустится после смены дизайна.</p>
        </div>
        <input
          id="classic-design"
          role="switch"
          type="checkbox"
          checked={classic}
          disabled={pending}
          onChange={() => void switchDesign()}
          className="interface-design-switch"
        />
      </div>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
