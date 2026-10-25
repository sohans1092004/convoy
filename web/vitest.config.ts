import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// Unit tests only; Playwright owns e2e/ and e2e-real/.
// Component tests render to static markup (react-dom/server): JSX with the automatic runtime and the @ alias.
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  resolve: { alias: { '@': fileURLToPath(new URL('.', import.meta.url)) } },
  test: { exclude: ['e2e/**', 'e2e-real/**', 'e2e-stories/**', 'node_modules/**'], passWithNoTests: true },
});
