import { defineConfig } from 'vitest/config';

// Unit tests only; Playwright owns e2e/ and e2e-real/.
export default defineConfig({ test: { exclude: ['e2e/**', 'e2e-real/**', 'e2e-stories/**', 'node_modules/**'], passWithNoTests: true } });
