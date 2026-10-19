import { expect, test, type Page } from "@playwright/test";

// Live checks of the security hardening in the admin UI (read-only: dialogs are dismissed, nothing is saved).
// The admin UI sends a Content-Security-Policy without inline scripts, so every page must run without CSP
// violations and the former inline handlers (confirm on submit, auto-submit, theme) must still work from admin.js.
const ADMIN_USER = process.env.JDBCODATA_ADMIN_USER ?? "admin";
const ADMIN_PASSWORD = process.env.JDBCODATA_ADMIN_PASSWORD ?? "";

test.describe.configure({ mode: "serial" });
test.skip(!ADMIN_PASSWORD, "JDBCODATA_ADMIN_PASSWORD not set");

/** Console errors (CSP violations are reported there as "Refused to …") and uncaught exceptions. */
function collectErrors(page: Page) {
  const errors: string[] = [];
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(`${page.url()}: ${m.text()}`);
  });
  page.on("pageerror", (e) => errors.push(`${page.url()}: ${e.message}`));
  return errors;
}

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("User").fill(ADMIN_USER);
  await page.getByLabel("Password").fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
}

test("security headers and session cookie", async ({ page, context }) => {
  const login_ = await page.goto("/login");
  const headers = login_!.headers();
  expect(headers["content-security-policy"]).toContain("script-src 'self'");
  expect(headers["content-security-policy"]).toContain("frame-ancestors 'none'");
  expect(headers["x-frame-options"]).toBe("DENY");
  expect(headers["strict-transport-security"]).toContain("max-age=");
  await login(page);
  const session = (await context.cookies()).find((c) => c.name === "JSESSIONID");
  expect(session, "session cookie").toBeDefined();
  expect(session!.secure).toBe(true);
  expect(session!.httpOnly).toBe(true);
  expect(session!.sameSite).toBe("Lax");
});

test("every admin page renders without CSP violations or script errors", async ({ page }) => {
  const errors = collectErrors(page);
  await login(page);
  const links = await page.getByRole("navigation").locator("a[href^='/admin']").evaluateAll((as) =>
    [...new Set(as.map((a) => (a as HTMLAnchorElement).getAttribute("href")!))]);
  expect(links.length).toBeGreaterThan(5);
  // plus pages with CodeMirror, htmx fragments and forms
  const extra = ["/admin/queries/new", "/admin/datasources/new", "/admin/settings", "/admin/search?q=Kunden"];
  for (const href of [...links, ...extra]) {
    const response = await page.goto(href);
    expect(response?.status(), href).toBeLessThan(400);
    await page.waitForLoadState("networkidle");
  }
  await page.goto("/admin/queries");
  await page.getByRole("link", { name: "Bestellungen", exact: true }).click();
  await expect(page.locator(".CodeMirror").first()).toBeVisible();
  expect(errors).toEqual([]);
});

test("delete confirmations come from admin.js and can be cancelled", async ({ page }) => {
  const errors = collectErrors(page);
  await login(page);
  await page.goto("/admin/cache");
  const posts: string[] = [];
  page.on("request", (r) => {
    if (r.method() === "POST") posts.push(r.url());
  });
  let question = "";
  page.once("dialog", async (d) => {
    question = d.message();
    await d.dismiss();
  });
  await page.getByRole("button", { name: "Invalidate all" }).click();
  await expect.poll(() => question).toBe("Clear the whole cache?");
  await page.waitForTimeout(500);
  expect(posts, "cancelled form must not be sent").toEqual([]);
  await expect(page).toHaveURL(/\/admin\/cache/);
  expect(errors).toEqual([]);
});

test("the statistics period submits on change", async ({ page }) => {
  await login(page);
  await page.goto("/admin/stats");
  await page.locator("select[data-autosubmit][name='period']").selectOption("7d");
  await expect(page).toHaveURL(/period=7d/);
  await expect(page.locator("select[name='period']")).toHaveValue("7d");
});

test("the theme choice survives a reload (theme.js before first paint)", async ({ page }) => {
  await login(page);
  const html = page.locator("html");
  const before = await html.getAttribute("data-theme");
  await page.locator("[data-theme-toggle]").click();
  const chosen = await html.getAttribute("data-theme");
  expect(chosen).toMatch(/^(dark|light)$/);
  expect(chosen).not.toBe(before);
  await page.reload();
  await expect(html).toHaveAttribute("data-theme", chosen!);
  await page.evaluate(() => localStorage.removeItem("jdbcodata-theme"));
});
