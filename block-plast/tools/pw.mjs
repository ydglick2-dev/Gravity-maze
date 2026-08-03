// Playwright מותקן גלובלית בסביבה הזו, ו-ESM לא מכבד NODE_PATH.
// לכן מאתרים את החבילה בזמן ריצה ומייבאים אותה לפי נתיב מלא.
import { execSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import fs from 'node:fs';
import path from 'node:path';

export async function loadPlaywright() {
  const roots = [];
  try { roots.push(execSync('npm root -g', { encoding: 'utf8' }).trim()); } catch {}
  roots.push(path.resolve('node_modules'));

  for (const root of roots) {
    for (const entry of ['index.mjs', 'index.js']) {
      const p = path.join(root, 'playwright', entry);
      if (fs.existsSync(p)) return import(pathToFileURL(p).href);
    }
  }
  throw new Error('playwright not found in: ' + roots.join(', '));
}
