import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  optimizeDeps: { include: ['heic-to/csp'] },
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom', maxWorkers: 2, exclude: [...configDefaults.exclude, 'e2e/**'] },
})
