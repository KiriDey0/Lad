// SPDX-License-Identifier: AGPL-3.0-only
import React from "react";
import { createRoot } from "react-dom/client";
import "./interface-design.css";
import { isClassicDesign } from "./InterfaceDesignSetting";
const App = React.lazy(() => isClassicDesign() ? import("./ClassicRenderer") : import("./orbit/Renderer"));
class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <div className="fatal-error">
        <h1>Интерфейс не смог загрузиться</h1>
        <p>Перезапусти Лад. Твоя библиотека остаётся в Яндекс Музыке.</p>
        <button onClick={() => location.reload()}>Перезагрузить</button>
      </div>
    ) : (
      this.props.children
    );
  }
}
createRoot(document.getElementById("root")!).render(
  <ErrorBoundary>
    <React.Suspense fallback={null}><App /></React.Suspense>
  </ErrorBoundary>,
);
