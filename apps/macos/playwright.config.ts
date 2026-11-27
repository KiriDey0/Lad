// SPDX-License-Identifier: AGPL-3.0-only
import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  testMatch: [
    "orbit-preview.spec.ts",
    "mobile-dock.spec.ts",
    "queue-interaction.spec.ts",
    "release-history.spec.ts",
    "ui.spec.ts",
    "underlay.spec.ts",
    "wave-volume.spec.ts",
    "remote-wave.spec.ts",
    "wave-appearance.spec.ts",
    "wave-budget.spec.ts",
    "device-identity.spec.ts",
    "local-library.spec.ts",
  ],
  timeout: 45000,
  workers: 1,
  reporter: "line",
  outputDir: "../../work/ui-test-results",
});
