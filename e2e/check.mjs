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
await expectText('Anmeldung fehlgeschlagen', 'Login mit falschem Passwort');
await page.fill('input[name=username]', 'admin');
await page.fill('input[name=password]', 'playwright-pw');
await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
if (!/\/admin(\?continue)?$/.test(page.url())) throw new Error("Nach Login nicht auf /admin: " + page.url());
await expectText('Übersicht', 'Dashboard');
await shot('02-dashboard');

// 2. Datasource connection test (htmx)
await page.click('nav >> text=Datenquellen');
await page.click('a:has-text("demo")');
await expectText('Verbindung testen', 'Datenquelle demo');
await page.fill('input[name=dbUser]', 'demo');
await page.fill('input[name=dbPassword]', 'demo');
await page.click('button:has-text("Testen")');
await expectText('Verbindung erfolgreich', 'htmx-Verbindungstest');
await shot('03-datasource');

// 3. Query editor: CodeMirror, parameter rows, test run, snapshot
await page.click('nav >> text=Queries');
await page.click('a:has-text("Sales")');
await page.waitForSelector('.CodeMirror');
log('CodeMirror-Editor gerendert');
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
await page.fill('#query-form input[name=dbUser]', 'demo');
await page.fill('#query-form input[name=dbPassword]', 'demo');
await page.fill('input[name=tp_p_year]', '2024');
await page.click('button:has-text("Testlauf")');
await expectText('Zeile(n)', 'htmx-Testlauf');
await page.click('button:has-text("Schema-Snapshot erstellen")');
await expectText('Snapshot erstellt', 'htmx-Schema-Snapshot');
await shot('04-query-editor');
await page.click('#query-form button[type=submit]');
await expectText('Query gespeichert', 'Query speichern');

// 4. New query via UI
await page.click('nav >> text=Queries');
await page.click('a:has-text("Neue Query")');
await page.waitForSelector('.CodeMirror');
await page.fill('input[name=name]', 'PwQuery');
await page.evaluate(() => { document.querySelector('.CodeMirror').CodeMirror.setValue('SELECT id, customer, amount FROM sales WHERE sale_year = 2025'); });
await page.click('#query-form button[type=submit]');
await expectText('angelegt', 'Neue Query anlegen');
await page.fill('#query-form input[name=dbUser]', 'demo');
await page.fill('#query-form input[name=dbPassword]', 'demo');
await page.click('button:has-text("Schema-Snapshot erstellen")');
await expectText('Snapshot erstellt: 3 Spalten', 'Snapshot der neuen Query');
const pwQueryUrl = page.url();

// 5. Service user with stored credentials
await page.click('nav >> text=Service-User');
await page.click('a:has-text("Neuer Service-User")');
await page.fill('input[name=username]', 'svc_pw');
await page.fill('input[name=displayName]', 'Playwright');
await page.fill('input[name=password]', 'playwright-user');
await page.click('form.card button[type=submit]');
await expectText('angelegt', 'Service-User anlegen');
await page.selectOption('select[name=datasourceId]', { label: 'demo' });
await page.fill('input[name=dbUsername]', 'demo');
await page.fill('input[name=dbPassword]', 'demo');
await page.click('button:has-text("Hinterlegen")');
await expectText('DB-Zugangsdaten hinterlegt', 'Credentials hinterlegen');
await shot('05-service-user');

// 6. Technical account
await page.click('nav >> text=Technische Accounts');
await page.click('a:has-text("Neuer Account")');
await page.fill('input[name=name]', 'tech_pw');
await page.selectOption('select[name=datasourceId]', { label: 'demo' });
await page.fill('input[name=dbUsername]', 'demo');
await page.fill('input[name=dbPassword]', 'demo');
await page.click('form.card button[type=submit]');
await expectText('angelegt', 'Technischen Account anlegen');
await page.click('button:has-text("Verbindung testen")');
await expectText('Verbindung erfolgreich', 'Verbindungstest technischer Account');

// 7. FIXED mode + grants on PwQuery, then OData access as service user
await page.goto(pwQueryUrl);
await page.selectOption('select[name=executionMode]', 'FIXED');
await page.selectOption('select[name=fixedCredentialId]', { index: 1 });
await page.click('#query-form button[type=submit]');
await expectText('Query gespeichert', 'FIXED-Modus speichern');
await page.check('input[name=all]');
await page.click('button:has-text("Freigaben speichern")');
await expectText('Freigaben gespeichert', 'Freigaben');
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
await shot('09-queries');
await page.click('nav >> text=Cache');
await expectText('Ergebnis-Cache', 'Cache-Seite');
await shot('10-cache');
await page.click('nav >> text=Statistik');
await expectText('Letzte Ausführungen', 'Statistik-Seite');
await expectText('PwQuery', 'Statistik enthält PwQuery-Ausführung');
await shot('07-stats');
await page.click('nav >> text=Audit');
await expectText('SET_GRANTS', 'Audit-Log enthält Freigabe');
await shot('11-audit');
await page.click('nav >> text=Admin-Konten');
await page.fill('form.card input[name=username]', 'pw_viewer');
await page.fill('form.card input[name=password]', 'viewer-pass-1');
await page.selectOption('form.card select[name=role]', 'VIEWER');
await page.click('form.card button[type=submit]');
await expectText('angelegt', 'Admin-Konto anlegen');
await shot('12-admins');
await page.click('nav >> text=Einstellungen');
await expectText('Master-Key', 'Einstellungen');
await shot('08-settings');
const active = await page.locator('nav a.active').innerText();
if (!/Einstellungen/.test(active)) throw new Error('Aktiver Navigationseintrag falsch: ' + active);
log('Aktiver Navigationseintrag markiert');
await page.setViewportSize({ width: 420, height: 860 });
await page.goto(`${base}/admin`);
await expectText('Übersicht', 'Dashboard (schmal)');
await shot('13-mobile-dashboard');
await page.setViewportSize({ width: 1280, height: 900 });

// 9. Logout and viewer login
await Promise.all([page.waitForNavigation(), page.click('button:has-text("Abmelden")')]);
if (!/\/login/.test(page.url())) throw new Error('Nach Logout nicht auf /login: ' + page.url());
await page.fill('input[name=username]', 'pw_viewer');
await page.fill('input[name=password]', 'viewer-pass-1');
await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);
if (!/\/admin$/.test(page.url())) throw new Error('Viewer-Login nicht auf /admin: ' + page.url());
await page.click('nav >> text=Queries');
await expectText('Sales', 'VIEWER sieht Query-Liste');
await page.goto(pwQueryUrl);
const resp = await page.request.post(pwQueryUrl + '/grants', { form: { all: 'on' }, headers: { 'X-CSRF-TOKEN': (await page.getAttribute('body', 'hx-headers')).match(/"X-CSRF-TOKEN": "([^"]+)"/)[1] } });
log(`VIEWER versucht Freigabe zu ändern: HTTP ${resp.status()}`);
if (resp.status() !== 403) throw new Error('VIEWER konnte schreiben');

await browser.close();
if (errors.length) { console.log('FEHLER:\n' + errors.join('\n')); process.exit(1); }
console.log('ALLE PRÜFUNGEN BESTANDEN');
