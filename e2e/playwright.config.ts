import { defineConfig, devices } from "@playwright/test";

// Against a running instance: E2E_BASE_URL (default the status20 demo), credentials from the environment
// (see README.md in this folder). Nothing is started here.
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  fullyParallel: false,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
  },
  projects: [{ name: "desktop", use: { ...devices["Desktop Chrome"] } }],
});
