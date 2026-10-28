// SPDX-License-Identifier: AGPL-3.0-only
import React from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/golos-text";
import "./style.css";
import App from "./App";
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
    <App />
  </ErrorBoundary>,
);
