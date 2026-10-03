import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom', maxWorkers: 2, exclude: [...configDefaults.exclude, 'e2e/**'] },
})
