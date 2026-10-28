// SPDX-License-Identifier: AGPL-3.0-only
import { defineConfig } from '@playwright/test';
import path from 'node:path';
const qa = process.env.LAD_QA_DIR || path.resolve(__dirname, 'qa');
export default defineConfig({testDir:'./tests',testMatch:['ui.spec.ts','windows.spec.ts','underlay.spec.ts'],timeout:60000,workers:1,reporter:[['line'],['json',{outputFile:path.join(qa,'playwright-results.json')}]],outputDir:path.join(qa,'ui-results')});
