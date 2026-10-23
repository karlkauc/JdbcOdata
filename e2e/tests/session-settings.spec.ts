import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

// Session settings and JDBC driver properties in the admin UI. Unlike the other specs this one writes: it creates a
// datasource and a query of its own (both named e2e_session_…) and deletes them again through the REST API.
// Needs E2E_WRITE=1 and a datasource on embedded H2 (the local demo, see README.md), whose only session key is
// TIME_ZONE; the offset of CURRENT_TIMESTAMP shows which setting the connection got.
const ADMIN_USER = process.env.JDBCODATA_ADMIN_USER ?? "admin";
const ADMIN_PASSWORD = process.env.JDBCODATA_ADMIN_PASSWORD ?? "";
const SOURCE = process.env.E2E_SOURCE_DATASOURCE ?? "demo";
const DB_USER = process.env.E2E_DB_USER ?? "demo";
const DB_PASSWORD = process.env.E2E_DB_PASSWORD ?? "demo";

const suffix = Date.now().toString(36);
const DATASOURCE = `e2e_session_${suffix}`;
const QUERY = `e2e_session_${suffix}`;
const OFFSET_SQL = "SELECT FORMATDATETIME(CURRENT_TIMESTAMP, 'XXX') AS tz";

test.describe.configure({ mode: "serial" });
test.skip(!process.env.E2E_WRITE, "writes to the instance: set E2E_WRITE=1");
test.skip(!ADMIN_PASSWORD, "JDBCODATA_ADMIN_PASSWORD not set");

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("User").fill(ADMIN_USER);
  await page.getByLabel("Password").fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
}

function api(request: APIRequestContext, method: "get" | "delete", path: string) {
  const auth = "Basic " + Buffer.from(`${ADMIN_USER}:${ADMIN_PASSWORD}`).toString("base64");
  return request[method](path, { headers: { Authorization: auth } });
}

test.afterAll(async ({ request }) => {
  const queries: { id: string; name: string }[] = await (await api(request, "get", "/api/v1/queries")).json();
  for (const q of queries.filter((q) => q.name === QUERY)) await api(request, "delete", `/api/v1/queries/${q.id}`);
  const sources: { id: string; name: string }[] = await (await api(request, "get", "/api/v1/datasources")).json();
  for (const d of sources.filter((d) => d.name === DATASOURCE)) await api(request, "delete", `/api/v1/datasources/${d.id}`);
});

test("datasource form validates, stores and applies session settings", async ({ page }) => {
  await login(page);
  await page.goto("/admin/datasources");
  await page.getByRole("link", { name: SOURCE, exact: true }).click();
  const jdbcUrl = await page.getByLabel("JDBC URL").inputValue();
  test.skip(!jdbcUrl.startsWith("jdbc:h2:"), `datasource ${SOURCE} is not H2: ${jdbcUrl}`);

  await page.goto("/admin/datasources/new");
  await page.getByLabel("Name").fill(DATASOURCE);
  await page.getByLabel("JDBC URL").fill(jdbcUrl);
  const session = page.getByLabel("Session settings");
  const driver = page.getByLabel("JDBC driver properties");

  // a key of another database: rejected with the keys allowed for H2, nothing is created
  await session.fill("NLS_SORT=GERMAN");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(".flash.err")).toContainText("NLS_SORT");
  await expect(page.locator(".flash.err")).toContainText("Allowed: TIME_ZONE");
  await expect(page).toHaveURL(/\/admin\/datasources$/);

  // the values stay in the form after the error; quotes would leave the generated literal
  await expect(session).toHaveValue("NLS_SORT=GERMAN");
  await session.fill("TIME_ZONE=+05:00'; DROP TABLE x");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(".flash.err")).toContainText("no quotes or semicolons");

  // driver properties that load classes or carry credentials are rejected
  await session.fill("TIME_ZONE=Mars/Nowhere");
  await driver.fill("socketFactory=org.example.Evil");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(".flash.err")).toContainText("'socketFactory' is not allowed");
  await driver.fill("password=secret");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(".flash.err")).toContainText("credential");

  // a syntactically valid but unknown time zone is saved; the connection test runs it and reports the error
  await driver.fill("DEFAULT_NULL_ORDERING=HIGH");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.locator(".flash.ok")).toContainText(`Datasource '${DATASOURCE}' created.`);
  await expect(page).toHaveURL(/\/admin\/datasources\/[0-9a-f-]{36}$/);
  await expect(session).toHaveValue("TIME_ZONE=Mars/Nowhere");
  await expect(driver).toHaveValue("DEFAULT_NULL_ORDERING=HIGH");
  await expect(page.getByText("Allowed: TIME_ZONE")).toBeVisible();

  await page.locator("input[name=dbUser]").fill(DB_USER);
  await page.locator("input[name=dbPassword]").fill(DB_PASSWORD);
  await page.getByRole("button", { name: "Test", exact: true }).click();
  await expect(page.locator("#test-result")).toContainText("Connection failed: Session settings rejected");

  await session.fill("TIME_ZONE=+05:00");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Datasource saved.")).toBeVisible();
  await expect(session).toHaveValue("TIME_ZONE=+05:00");
  await page.locator("input[name=dbUser]").fill(DB_USER);
  await page.locator("input[name=dbPassword]").fill(DB_PASSWORD);
  await page.getByRole("button", { name: "Test", exact: true }).click();
  await expect(page.locator("#test-result")).toContainText("Connection successful");
});

test("query inherits the datasource settings and overrides single keys", async ({ page }) => {
  await login(page);
  await page.goto("/admin/queries/new");
  await page.waitForSelector(".CodeMirror");
  await page.locator("input[name=name]").fill(QUERY);
  await page.locator("select[name=datasourceId]").selectOption({ label: DATASOURCE });
  await page.evaluate((sql) => {
    (document.querySelector(".CodeMirror") as any).CodeMirror.setValue(sql);
  }, OFFSET_SQL);
  await page.locator("#query-form input[name=dbUser]").fill(DB_USER);
  await page.locator("#query-form input[name=dbPassword]").fill(DB_PASSWORD);
  const overrides = page.locator("textarea[name=sessionSettings]");
  const result = page.locator("#test-result");
  const runTest = () => page.locator('#test-card button:has-text("Test run")').click();

  // without overrides: the datasource default
  await runTest();
  await expect(result).toContainText("1 row(s)");
  await expect(result.locator("tbody td").first()).toHaveText("+05:00");

  // an override from the unsaved form applies to the test run
  await overrides.fill("TIME_ZONE=-03:00");
  await runTest();
  await expect(result.locator("tbody td").first()).toHaveText("-03:00");

  // saved with the override; a key of another database is refused on save
  await page.locator(".savebar button[type=submit]").click();
  await expect(page).toHaveURL(/\/admin\/queries\/[0-9a-f-]{36}/);
  const queryUrl = page.url().split("#")[0];
  await expect(overrides).toHaveValue("TIME_ZONE=-03:00");
  await overrides.fill("NLS_SORT=GERMAN");
  await page.locator(".savebar button[type=submit]").click();
  await expect(page.locator(".flash.err")).toContainText("NLS_SORT");

  // the refused value was not stored; removing the override falls back to the datasource
  await page.goto(queryUrl);
  await expect(overrides).toHaveValue("TIME_ZONE=-03:00");
  await overrides.fill("");
  await page.locator("#query-form input[name=dbUser]").fill(DB_USER);
  await page.locator("#query-form input[name=dbPassword]").fill(DB_PASSWORD);
  await runTest();
  await expect(result.locator("tbody td").first()).toHaveText("+05:00");
});
