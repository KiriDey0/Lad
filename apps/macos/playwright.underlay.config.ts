// SPDX-License-Identifier: AGPL-3.0-only
import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests',testMatch:'underlay.spec.ts',timeout:60000,workers:1,reporter:'line',outputDir:'../../work/underlay-test-results'});
