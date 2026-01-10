import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      exclude: [
        ...configDefaults.exclude,
        'e2e/*',
        'tests/admin/OrdersDashboard.spec.ts',
        'tests/admin/ReportingDashboard.spec.ts',
        'tests/admin/PlatformConsole.spec.ts',
      ],
      root: fileURLToPath(new URL('./', import.meta.url)),
      setupFiles: ['./tests/setup.ts'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'json', 'html'],
        exclude: [
          'node_modules/',
          'src/api/generated/**',
          '**/*.spec.ts',
          '**/*.stories.ts',
          '.storybook/**'
        ]
      }
    }
  })
)
