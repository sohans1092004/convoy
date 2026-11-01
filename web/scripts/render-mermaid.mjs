// Render a Mermaid file to SVG in headless Chromium (Mermaid 11.4.1 from jsDelivr; no npm dependency).
//   node scripts/render-mermaid.mjs ../docs/architecture/overview.mmd ../docs/architecture/overview.svg
import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';

const [src, out] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage();
await page.setContent('<script src="https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"></script>');
await page.waitForFunction(() => window.mermaid);
const svg = await page.evaluate(async (text) => {
  window.mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'strict', flowchart: { htmlLabels: false } });
  return (await window.mermaid.render('d', text)).svg.replace(/<br>/g, '<br/>');
}, readFileSync(src, 'utf8'));
writeFileSync(out, svg + '\n');
await browser.close();
console.log(`${out} (${svg.length} bytes)`);
