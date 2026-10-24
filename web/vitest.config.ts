import { defineConfig } from 'vitest/config';

// Unit tests only; Playwright owns e2e/ and e2e-real/.
export default defineConfig({ test: { exclude: ['e2e/**', 'e2e-real/**', 'node_modules/**'], passWithNoTests: true } });
