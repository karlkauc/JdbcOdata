import { expect, test, type Page } from "@playwright/test";

// Live checks of a running JdbcOdata: admin UI (read-only use) and the OData feed in the browser.
const ADMIN_USER = process.env.JDBCODATA_ADMIN_USER ?? "admin";
const ADMIN_PASSWORD = process.env.JDBCODATA_ADMIN_PASSWORD ?? "";
const USER = process.env.JDBCODATA_USER ?? "excel_demo";
const PASSWORD = process.env.JDBCODATA_PASSWORD ?? "";
const NORD_PASSWORD = process.env.JDBCODATA_NORD_PASSWORD ?? "";

test.describe.configure({ mode: "serial" });

/**
 * Credentials sent with every request. Browsers (and Playwright's httpCredentials) only answer a 401 challenge; a
 * service with anonymous access (JDBCODATA_ODATA_ANONYMOUS_USER) never sends one, so they would stay anonymous.
 */
function basic(user: string, password: string) {
  return { Authorization: "Basic " + Buffer.from(`${user}:${password}`).toString("base64") };
}

async function login(page: Page) {
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel("Benutzer").fill(ADMIN_USER);
  await page.getByLabel("Passwort").fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Anmelden" }).click();
  await expect(page.getByRole("heading", { name: "Übersicht" })).toBeVisible();
}

test.describe("admin UI", () => {
  test.skip(!ADMIN_PASSWORD, "JDBCODATA_ADMIN_PASSWORD not set");

  test("wrong password stays on the login page", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("Benutzer").fill(ADMIN_USER);
    await page.getByLabel("Passwort").fill("falsch");
    await page.getByRole("button", { name: "Anmelden" }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole("heading", { name: "Übersicht" })).toHaveCount(0);
  });

  test("navigation reaches every page", async ({ page }) => {
    await login(page);
    for (const [link, heading] of [
      ["Datenquellen", /Datenquellen/],
      ["Queries", /Queries/],
      ["Cache", /Cache/],
      ["Statistik", /Statistik/],
      ["Audit", /Audit/],
    ] as const) {
      await page.getByRole("navigation").getByRole("link", { name: link, exact: false }).first().click();
      await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
    }
  });

  test("the Bestellungen query shows its structured columns", async ({ page }) => {
    await login(page);
    await page.goto("/admin/queries");
    await page.getByRole("link", { name: "Bestellungen", exact: true }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Bestellungen" })).toBeVisible();
    const kategorien = page.locator('select[name="collection_Kategorien"]');
    await expect(kategorien).toHaveValue("PRIMITIVE");
    await expect(kategorien.locator("option:checked")).toHaveText("Collection(Edm.String)");
    await expect(page.locator('select[name="collection_Positionen"]')).toHaveValue("COMPLEX");
    await expect(page.locator('select[name="collection_Kundendaten"]')).toHaveValue("OBJECT");
    await expect(page.locator('input[name="textcopy_Kategorien"]')).toBeChecked();
    const structure = page.locator('textarea[name="structure_Positionen"]');
    await expect(structure).toHaveValue(/Betrag Edm\.Decimal/);
    await expect(structure).toHaveValue(/Produkt Edm\.String/);
    await expect(page.locator('input[name="key_BestellungId"]')).toBeChecked();
  });

  test("statistics list recent executions", async ({ page }) => {
    await login(page);
    await page.goto("/admin/stats");
    await expect(page.getByRole("heading", { name: "Letzte Ausführungen" })).toBeVisible();
    await expect(page.getByRole("cell", { name: /Bestellungen|Kunden|Umsatz/ }).first()).toBeVisible();
  });
});

test.describe("OData feed in the browser", () => {
  test.skip(!PASSWORD, "JDBCODATA_PASSWORD not set");
  test.use({ extraHTTPHeaders: basic(USER, PASSWORD) });

  /** Browsers ask for XML first (and get it); $format=json selects JSON. */
  async function json(page: Page, path: string) {
    const response = await page.goto("/odata/" + path + (path.includes("?") ? "&" : "?") + "$format=json");
    expect(response?.status(), path).toBe(200);
    return response!.json();
  }

  test("service document and metadata", async ({ page }) => {
    const xmlDoc = await page.goto("/odata/");
    expect(xmlDoc?.headers()["content-type"]).toContain("xml");
    const doc = await json(page, "");
    expect(doc.value.map((s: { name: string }) => s.name)).toEqual(
      expect.arrayContaining(["Bestellungen", "Kunden", "Organisation", "Produkte", "Umsatz", "UmsatzProMonat"]),
    );
    await page.goto("/odata/$metadata");
    const xml = await page.content();
    expect(xml).toContain("Collection(JdbcOdata.Bestellungen_Positionen)");
    expect(xml).toContain('Type="JdbcOdata.Bestellungen_Kundendaten"');
    expect(xml).toContain('Name="KategorienText"');
  });

  test("collections, complex values and $select options", async ({ page }) => {
    const body = await json(
      page,
      "Bestellungen?$top=2&$orderby=BestellungId&$filter=Kategorien/any(k:k%20eq%20'Infrastruktur')" +
        "&$select=BestellungId,Kundendaten/Land,Kategorien,Positionen($orderby=Betrag%20desc;$top=1;$count=true)",
    );
    expect(body.value).toHaveLength(2);
    for (const r of body.value) {
      expect(r.Kategorien).toContain("Infrastruktur");
      expect(Object.keys(r.Kundendaten)).toEqual(["Land"]);
      expect(r.Positionen).toHaveLength(1);
      expect(r["Positionen@odata.count"]).toBeGreaterThanOrEqual(1);
    }
    const id = body.value[0].BestellungId;
    const all = await json(page, `Bestellungen(${id})/Positionen?$orderby=Betrag%20desc`);
    expect(all.value).toHaveLength(body.value[0]["Positionen@odata.count"]);
    expect(all.value[0].Betrag).toBe(body.value[0].Positionen[0].Betrag);
    const count = await page.goto(`/odata/Bestellungen(${id})/Positionen/$count`);
    expect(await count?.text()).toBe(String(all.value.length));
    const land = await page.goto(`/odata/Bestellungen(${id})/Kundendaten/Land/$value`);
    expect(await land?.text()).toMatch(/^[A-Z]{2}$/);
  });

  test("push-down header and $apply", async ({ page }) => {
    const response = await page.goto("/odata/Umsatz?$filter=Region%20eq%20'Nord'&$top=1&$count=true");
    expect(response?.headers()["x-push-down"]).toContain("filter");
    const grouped = await json(page, "Umsatz?$apply=groupby((Region),aggregate($count%20as%20N))&$orderby=Region");
    expect(grouped.value.map((r: { Region: string }) => r.Region)).toEqual(["Nord", "Ost", "Süd", "West"]);
  });
});

test.describe("row-level security", () => {
  test.skip(!NORD_PASSWORD || !PASSWORD, "JDBCODATA_NORD_PASSWORD not set");
  test.use({ extraHTTPHeaders: basic("excel_nord", NORD_PASSWORD) });

  test("excel_nord sees only customers of Nord", async ({ page }) => {
    const response = await page.goto("/odata/Kunden?$count=true&$top=500&$select=Region&$format=json");
    expect(response?.status()).toBe(200);
    const body = await response!.json();
    expect(body["@odata.count"]).toBeGreaterThan(0);
    expect(new Set(body.value.map((r: { Region: string }) => r.Region))).toEqual(new Set(["Nord"]));
  });
});

test.describe("anonymous access", () => {
  test("without credentials the feed answers as the anonymous service user (all regions)", async ({ page }) => {
    const response = await page.goto("/odata/Kunden?$apply=groupby((Region))&$format=json");
    test.skip(response?.status() === 401, "anonymous access is not enabled on this instance");
    expect(response?.status()).toBe(200);
    const body = await response!.json();
    expect(body.value.length).toBe(4);
    // with a wrong password there is no fallback to anonymous
    const wrong = await page.request.get("/odata/Kunden?$top=1", { headers: basic(USER, "falsch") });
    expect(wrong.status()).toBe(401);
  });
});
