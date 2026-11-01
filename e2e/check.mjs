import { chromium, request } from 'playwright';
import fs from 'node:fs';

const port = fs.readFileSync(process.env.PW_DIR + '/port.txt', 'utf8').trim();
const base = `http://localhost:${port}`;
const shots = process.env.PW_DIR + '/shots';
const errors = [];
const log = (m) => console.log('• ' + m);

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'de-DE' });
const page = await ctx.newPage();
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
page.on('response', (r) => { if (r.status() >= 500) errors.push(`HTTP ${r.status()} ${r.url()}`); });

async function shot(name) { await page.screenshot({ path: `${shots}/${name}.png`, fullPage: true }); }
async function expectText(text, what) {
  await page.getByText(text, { exact: false }).first().waitFor({ timeout: 10000 });
  log(`${what}: "${text}" sichtbar`);
}

// 1. Login
await page.goto(`${base}/admin`);
await page.waitForURL(/\/login/);
await shot('01-login');
await page.fill('input[name=username]', 'admin');
await page.fill('input[name=password]', 'wrong');
await page.click('button[type=submit]');
await expectText('Sign-in failed', 'Login mit falschem Passwort');
await page.fill('input[name=username]', 'admin');
await page.fill('input[name=password]', 'playwright-pw');
await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
if (!/\/admin(\?continue)?$/.test(page.url())) throw new Error("Nach Login nicht auf /admin: " + page.url());
await expectText('Overview', 'Dashboard');
await expectText('Needs attention', 'Dashboard-Hinweise');
await shot('02-dashboard');

// 1b. Global search (Ctrl+K) with keyboard selection
await page.keyboard.press('Control+k');
await page.keyboard.type('sal');
await page.waitForSelector('.search-results.open .search-hit.selected');
log('Globale Suche: Treffer mit Strg+K');
await page.keyboard.press('Escape');

// 2. Datasource connection test (htmx)
await page.click('nav >> text=Datasources');
await page.click('a:has-text("demo")');
await expectText('Test connection', 'Datenquelle demo');
await page.fill('input[name=dbUser]', 'demo');
await page.fill('input[name=dbPassword]', 'demo');
await page.click('button:text-is("Test")');
await expectText('Connection successful', 'htmx-Verbindungstest');
await shot('03-datasource');

// 3. Query editor: CodeMirror, parameter rows, test run, snapshot
await page.click('nav >> text=Queries');
await page.click('a:has-text("Sales")');
await page.waitForSelector('.CodeMirror');
log('CodeMirror-Editor gerendert');
await page.fill('input[name=cacheTtlSec]', '1800');
await expectText('Unsaved changes: Cache TTL', 'Speichern-Leiste zeigt Änderung');
await page.click('[data-tab=parameter]');
const rowsBefore = await page.locator('tr.param-row').count();
await page.click('#add-param');
const rowsAfter = await page.locator('tr.param-row').count();
if (rowsAfter !== rowsBefore + 1) throw new Error('Parameterzeile nicht hinzugefügt');
await page.locator('tr.param-row').last().locator('.p-name').fill('p_new');
const testName = await page.locator('tr.param-row').last().locator('.p-test').getAttribute('name');
if (testName !== 'tp_p_new') throw new Error('Testwert-Feld nicht synchronisiert: ' + testName);
await page.locator('tr.param-row').last().locator('.remove-param').click();
if ((await page.locator('tr.param-row').count()) !== rowsBefore) throw new Error('Parameterzeile nicht entfernt');
log('Parameterzeilen hinzufügen/entfernen funktioniert');
await page.fill('input[name=tp_p_year]', '2024');
await page.click('[data-tab=definition]');
await page.fill('#query-form input[name=dbUser]', 'demo');
await page.fill('#query-form input[name=dbPassword]', 'demo');
await page.click('#test-card button:has-text("Test run")');
await expectText('row(s)', 'htmx-Testlauf');
await shot('04-query-editor');
await page.click('button:has-text("Take schema snapshot")');
await expectText('Snapshot taken', 'htmx-Schema-Snapshot');
if (!(await page.locator('[data-tab=spalten].active').count())) throw new Error('Nach dem Snapshot nicht im Tab Spalten');
await page.click('.savebar button[type=submit]');
await expectText('Query saved', 'Query speichern');

// 4. New query via UI
await page.click('nav >> text=Queries');
await page.click('a:has-text("New query")');
await page.waitForSelector('.CodeMirror');
await page.fill('input[name=name]', 'PwQuery');
await page.evaluate(() => { document.querySelector('.CodeMirror').CodeMirror.setValue('SELECT id, customer, amount FROM sales WHERE sale_year = 2025'); });
await page.click('.savebar button[type=submit]');
await expectText('created', 'Neue Query anlegen');
await page.fill('#query-form input[name=dbUser]', 'demo');
await page.fill('#query-form input[name=dbPassword]', 'demo');
await page.click('button:has-text("Take schema snapshot")');
await expectText('Snapshot taken: 3 columns', 'Snapshot der neuen Query');
const pwQueryUrl = page.url();

// 5. Service user with stored credentials
await page.click('nav >> text=Service users');
await page.click('a:has-text("New service user")');
await page.fill('input[name=username]', 'svc_pw');
await page.fill('input[name=displayName]', 'Playwright');
await page.fill('input[name=password]', 'playwright-user');
await page.click('form.card button[type=submit]');
await expectText('created', 'Service-User anlegen');
await page.selectOption('select[name=datasourceId]', { label: 'demo' });
await page.fill('input[name=dbUsername]', 'demo');
await page.fill('input[name=dbPassword]', 'demo');
await page.click('button:text-is("Store")');
await expectText('Database credentials stored', 'Credentials hinterlegen');
await shot('05-service-user');

// 6. Technical account
await page.click('nav >> text=Technical accounts');
await page.click('a:has-text("New account")');
await page.fill('input[name=name]', 'tech_pw');
await page.selectOption('select[name=datasourceId]', { label: 'demo' });
await page.fill('input[name=dbUsername]', 'demo');
await page.fill('input[name=dbPassword]', 'demo');
await page.click('form.card button[type=submit]');
await expectText('created', 'Technischen Account anlegen');
await page.click('button:has-text("Test connection")');
await expectText('Connection successful', 'Verbindungstest technischer Account');

// 7. FIXED mode + grants on PwQuery, then OData access as service user
await page.goto(pwQueryUrl.split('#')[0] + '#definition');
await page.click('.segmented label:has-text("FIXED")');
await page.selectOption('select[name=fixedCredentialId]', { index: 1 });
await page.click('.savebar button[type=submit]');
await expectText('Query saved', 'FIXED-Modus speichern');
await page.click('[data-tab=freigaben]');
await page.check('input[name=all]');
await page.click('.savebar button[type=submit]');
await expectText('Query saved', 'Freigaben über die Speichern-Leiste');
await shot('06-fixed-query');

const api = await request.newContext({ baseURL: base });
const auth = (u, p) => ({ Authorization: 'Basic ' + Buffer.from(`${u}:${p}`).toString('base64') });
const feed = await api.get('/odata/PwQuery?$top=2&$orderby=amount desc', { headers: auth('svc_pw', 'playwright-user') });
if (feed.status() !== 200) throw new Error('OData als Service-User: ' + feed.status() + ' ' + await feed.text());
const feedJson = await feed.json();
log(`OData-Feed als svc_pw: ${feedJson.value.length} Zeilen, X-Cache=${feed.headers()['x-cache']}`);
const sales = await api.get('/odata/Sales/$count', { headers: auth('svc_pw', 'playwright-user') });
log(`Sales/$count über hinterlegte Credentials: ${await sales.text()} (HTTP ${sales.status()})`);
const denied = await api.get('/odata/Sales', { headers: auth('svc_pw', 'bad') });
log(`falsches Service-Passwort: HTTP ${denied.status()}`);
const noauth = await api.get('/odata/');
log(`ohne Auth: HTTP ${noauth.status()}, WWW-Authenticate=${noauth.headers()['www-authenticate']}`);

// 8. Operations pages
await page.click('nav >> text=Queries');
await expectText('PwQuery', 'Query-Liste');
await page.selectOption('select[name=mode]', 'FIXED');
await page.waitForURL(/mode=FIXED/);
if (await page.locator('a.row-title:has-text("Sales")').count()) throw new Error('Filter Modus=FIXED zeigt CALLER-Query');
log('Filter-Chips filtern die Query-Liste');
await page.goto(`${base}/admin/queries`);
await shot('09-queries');
await page.click('nav >> text=Cache');
await expectText('Result cache', 'Cache-Seite');
await shot('10-cache');
await page.click('nav >> text=Statistics');
await expectText('Recent executions', 'Statistik-Seite');
await expectText('PwQuery', 'Statistik enthält PwQuery-Ausführung');
await shot('07-stats');
await page.click('nav >> text=Audit');
await expectText('SET_GRANTS', 'Audit-Log enthält Freigabe');
await shot('11-audit');
await page.click('nav >> text=Admin accounts');
await page.fill('form.card input[name=username]', 'pw_viewer');
await page.fill('form.card input[name=password]', 'viewer-pass-1');
await page.selectOption('form.card select[name=role]', 'VIEWER');
await page.click('form.card button[type=submit]');
await expectText('created', 'Admin-Konto anlegen');
await shot('12-admins');
await page.click('nav >> text=Settings');
await expectText('Master key', 'Einstellungen');
await shot('08-settings');
const active = await page.locator('nav a.active').innerText();
if (!/Settings/.test(active)) throw new Error('Aktiver Navigationseintrag falsch: ' + active);
log('Aktiver Navigationseintrag markiert');
await page.setViewportSize({ width: 420, height: 860 });
await page.goto(`${base}/admin`);
await expectText('Overview', 'Dashboard (schmal)');
await shot('13-mobile-dashboard');
await page.setViewportSize({ width: 1280, height: 900 });

// 9. Logout and viewer login
await page.click('.user-menu summary');
await Promise.all([page.waitForNavigation(), page.click('button:has-text("Sign out")')]);
if (!/\/login/.test(page.url())) throw new Error('Nach Logout nicht auf /login: ' + page.url());
await page.fill('input[name=username]', 'pw_viewer');
await page.fill('input[name=password]', 'viewer-pass-1');
await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
if (!/\/admin\/password$/.test(page.url())) throw new Error('Neues Konto nicht zum Passwortwechsel geleitet: ' + page.url());
await page.goto(`${base}/admin/queries`);
if (!/\/admin\/password$/.test(page.url())) throw new Error('Passwortwechsel lässt sich umgehen: ' + page.url());
await page.fill('input[name=current]', 'viewer-pass-1');
await page.fill('input[name=password]', 'viewer-pass-2');
await page.fill('input[name=confirm]', 'viewer-pass-2');
await Promise.all([page.waitForNavigation(), page.click('main button[type=submit]')]);
if (!/\/admin$/.test(page.url())) throw new Error('Nach Passwortwechsel nicht auf /admin: ' + page.url());
await page.click('nav >> text=Queries');
await expectText('Sales', 'VIEWER sieht Query-Liste');
await page.goto(pwQueryUrl);
const resp = await page.request.post(pwQueryUrl + '/grants', { form: { all: 'on' }, headers: { 'X-CSRF-TOKEN': (await page.getAttribute('body', 'hx-headers')).match(/"X-CSRF-TOKEN": "([^"]+)"/)[1] } });
log(`VIEWER versucht Freigabe zu ändern: HTTP ${resp.status()}`);
if (resp.status() !== 403) throw new Error('VIEWER konnte schreiben');

await browser.close();
if (errors.length) { console.log('FEHLER:\n' + errors.join('\n')); process.exit(1); }
console.log('ALLE PRÜFUNGEN BESTANDEN');
