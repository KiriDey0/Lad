export interface UpdateState {
  phase: string;
  currentVersion: string;
  version?: string;
  notes?: string;
  message?: string;
  progress: number;
  size?: number;
}
import { Container } from "@kiridey/orbit-ui";
export function UpdateSection() {
  return (
    <Container className="settings-section orbit-preview-info">
      <h2>Лад Orbit</h2>
      <p className="settings-about">
        Тестовая сборка на базе Лада 0.6.4 · Orbit UI 0.2.0.
      </p>
      <p className="settings-about">
        Отдельные настройки и очередь. Основное приложение продолжает получать
        стабильные обновления.
      </p>
    </Container>
  );
}
