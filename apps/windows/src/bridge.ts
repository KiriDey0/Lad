// SPDX-License-Identifier: AGPL-3.0-only
import { browserBridge } from "./demo";
export const bridge = window.lad || browserBridge();
