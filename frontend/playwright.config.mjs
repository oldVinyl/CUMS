import { defineConfig } from "@playwright/test";
const widths = [320, 375, 768, 1024, 1440];
export default defineConfig({
  testDir: "./e2e", timeout: 30000, fullyParallel: true, workers: 2,
  reporter: [["list"], ["html", { outputFolder: "artifacts/playwright-report", open: "never" }]],
  outputDir: "artifacts/playwright-results",
  use: { baseURL: "http://127.0.0.1:5173", trace: "retain-on-failure", launchOptions: process.env.CUMS_CHROME_PATH ? { executablePath: process.env.CUMS_CHROME_PATH } : {} },
  projects: widths.flatMap(width => ["light", "dark"].map(colorScheme => ({ name: `${width}-${colorScheme}`, use: { viewport: { width, height: 1000 }, colorScheme } }))),
  webServer: { command: "npm run dev -- --host 127.0.0.1", url: "http://127.0.0.1:5173", reuseExistingServer: !process.env.CI, timeout: 30000 },
});
