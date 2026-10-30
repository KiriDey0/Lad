// SPDX-License-Identifier: AGPL-3.0-only
import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests',testMatch:['ui.spec.ts','underlay.spec.ts','wave-volume.spec.ts','remote-wave.spec.ts'],timeout:45000,workers:1,reporter:'line',outputDir:'../../work/ui-test-results'});
