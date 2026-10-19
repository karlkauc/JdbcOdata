(function () {
  document.documentElement.classList.add('js');
  var isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

  // ---------- Theme: "system" (no data-theme, follows the OS), "light" or "dark"; kept per browser ----------
  var THEME_KEY = 'jdbcodata-theme';
  var darkQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  var themeListeners = [];
  function storedTheme() { try { return localStorage.getItem(THEME_KEY) || 'system'; } catch (e) { return 'system'; } }
  function isDark() {
    var attr = document.documentElement.getAttribute('data-theme');
    return attr ? attr === 'dark' : !!(darkQuery && darkQuery.matches);
  }
  function setTheme(choice) {
    try { if (choice === 'system') localStorage.removeItem(THEME_KEY); else localStorage.setItem(THEME_KEY, choice); } catch (e) { /* not persisted */ }
    if (choice === 'system') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', choice);
    syncTheme();
  }
  function syncTheme() {
    var choice = storedTheme();
    document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-theme-choice') === choice);
      b.setAttribute('aria-pressed', b.getAttribute('data-theme-choice') === choice);
    });
    themeListeners.forEach(function (fn) { fn(isDark()); });
  }
  document.addEventListener('click', function (e) {
    var choice = e.target.closest('[data-theme-choice]');
    if (choice) { setTheme(choice.getAttribute('data-theme-choice')); return; }
    if (e.target.closest('[data-theme-toggle]')) setTheme(isDark() ? 'light' : 'dark');
  });
  if (darkQuery && darkQuery.addEventListener) darkQuery.addEventListener('change', syncTheme);
  syncTheme();

  // ---------- Toasts ----------
  var CHECK_ICON = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';
  var ALERT_ICON = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3 2 20h20zM12 10v4M12 17.5v.5"/></svg>';
  function toast(text, kind) {
    var box = document.getElementById('toasts');
    if (!box) return;
    var el = document.createElement('div');
    el.className = 'toast' + (kind === 'err' ? ' err' : '');
    el.innerHTML = kind === 'err' ? ALERT_ICON : CHECK_ICON;
    el.appendChild(document.createTextNode(text));
    box.appendChild(el);
    setTimeout(function () { el.classList.add('hide'); }, 2600);
    setTimeout(function () { el.remove(); }, 3000);
  }
  window.jdbcodataToast = toast;

  // ---------- Copy: data-copy="#id" copies the element's text, data-copy-text="..." copies the value ----------
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (resolve, reject) {
      var area = document.createElement('textarea');
      area.value = text;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try { document.execCommand('copy') ? resolve() : reject(); } catch (e) { reject(e); }
      area.remove();
    });
  }
  document.addEventListener('click', function (e) {
    var button = e.target.closest('[data-copy], [data-copy-text]');
    if (!button) return;
    var text = button.hasAttribute('data-copy-text') ? button.getAttribute('data-copy-text')
      : (document.querySelector(button.getAttribute('data-copy')) || {}).textContent;
    if (!text) return;
    text = text.trim();
    copyText(text).then(function () { toast('Copied: ' + text); }, function () { toast('Copying is not possible here', 'err'); });
  });

  // ---------- Menus (details.menu): close on outside click and Escape ----------
  document.addEventListener('click', function (e) {
    document.querySelectorAll('details.menu[open]').forEach(function (d) { if (!d.contains(e.target)) d.removeAttribute('open'); });
  });

  // ---------- Global search ----------
  var search = document.getElementById('global-search');
  var results = document.getElementById('search-results');
  function hits() { return results ? Array.prototype.slice.call(results.querySelectorAll('.search-hit')) : []; }
  function closeResults() { if (results) { results.classList.remove('open'); } }
  function select(index) {
    var list = hits();
    list.forEach(function (h, i) { h.classList.toggle('selected', i === index); });
    if (list[index]) list[index].scrollIntoView({ block: 'nearest' });
  }
  if (search && results) {
    document.body.addEventListener('htmx:afterSwap', function (e) {
      if (e.detail.target !== results) return;
      results.classList.toggle('open', search.value.trim() !== '');
      select(0);
    });
    search.addEventListener('keydown', function (e) {
      var list = hits();
      var current = list.findIndex(function (h) { return h.classList.contains('selected'); });
      if (e.key === 'ArrowDown') { e.preventDefault(); select(Math.min(list.length - 1, current + 1)); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); select(Math.max(0, current - 1)); }
      else if (e.key === 'Enter' && current >= 0 && results.classList.contains('open')) { e.preventDefault(); list[current].click(); }
      else if (e.key === 'Escape') { closeResults(); search.blur(); }
    });
    search.addEventListener('focus', function () { if (search.value.trim() && results.innerHTML.trim()) results.classList.add('open'); });
    search.addEventListener('search', function () { if (!search.value) closeResults(); });
    document.addEventListener('click', function (e) { if (!e.target.closest('.global-search')) closeResults(); });
    var kbd = document.querySelector('.search-kbd');
    if (kbd && !isMac) kbd.textContent = 'Ctrl K';
  }

  if (!isMac) document.querySelectorAll('kbd[data-mod]').forEach(function (k) { k.textContent = k.textContent.replace('⌘', 'Ctrl+'); });

  // ---------- Keyboard shortcuts: Cmd/Ctrl+K search, Cmd/Ctrl+S save ----------
  document.addEventListener('keydown', function (e) {
    var mod = e.metaKey || e.ctrlKey;
    if (mod && (e.key === 'k' || e.key === 'K') && search) {
      e.preventDefault();
      search.focus();
      search.select();
    } else if (mod && (e.key === 's' || e.key === 'S')) {
      var form = document.querySelector('form[data-dirty-form]');
      if (form) { e.preventDefault(); form.requestSubmit(); }
    } else if (e.key === 'Escape') {
      document.querySelectorAll('details.menu[open]').forEach(function (d) { d.removeAttribute('open'); });
    }
  });

  // ---------- Filter forms: selects submit on change ----------
  document.querySelectorAll('form[data-autosubmit]').forEach(function (form) {
    form.addEventListener('change', function (e) { if (e.target.tagName === 'SELECT') form.requestSubmit(); });
    form.querySelectorAll('input[type=search]').forEach(function (input) {
      input.addEventListener('search', function () { if (!input.value) form.requestSubmit(); });
    });
  });

  // ---------- Client-side table filter: <input data-filter-table="#table"> ----------
  document.querySelectorAll('input[data-filter-table]').forEach(function (input) {
    var table = document.querySelector(input.getAttribute('data-filter-table'));
    if (!table) return;
    input.addEventListener('input', function () {
      var needle = input.value.trim().toLowerCase();
      table.querySelectorAll('tbody tr').forEach(function (row) {
        if (row.querySelector('td.empty')) return;
        row.style.display = !needle || row.textContent.toLowerCase().indexOf(needle) >= 0 ? '' : 'none';
      });
    });
  });

  // ---------- SQL editor ----------
  var cm = null;
  var sqlArea = document.getElementById('sql');
  if (sqlArea && window.CodeMirror) {
    cm = CodeMirror.fromTextArea(sqlArea, { mode: 'text/x-sql', theme: isDark() ? 'material-darker' : 'eclipse', lineNumbers: true, viewportMargin: Infinity });
    themeListeners.push(function (dark) { cm.setOption('theme', dark ? 'material-darker' : 'eclipse'); });
    cm.on('change', function () {
      cm.save();
      sqlArea.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }

  // ---------- Tabs (query editor) ----------
  var editor = document.querySelector('[data-editor-key]');
  var tabLinks = Array.prototype.slice.call(document.querySelectorAll('.tabs [data-tab]'));
  function panel(name) { return document.querySelector('[data-tab-panel="' + name + '"]'); }
  function activate(name, remember) {
    if (!panel(name)) name = 'definition';
    tabLinks.forEach(function (a) { a.classList.toggle('active', a.getAttribute('data-tab') === name); a.setAttribute('aria-selected', a.getAttribute('data-tab') === name); });
    document.querySelectorAll('[data-tab-panel]').forEach(function (p) { p.classList.toggle('active', p.getAttribute('data-tab-panel') === name); });
    if (remember !== false) {
      history.replaceState(null, '', '#' + name);
      try { sessionStorage.setItem('tab:' + editor.getAttribute('data-editor-key'), name); } catch (e) { /* storage unavailable */ }
    }
    if (name === 'definition' && cm) cm.refresh();
  }
  if (editor && tabLinks.length) {
    editor.classList.add('tabs-ready');
    var initial = location.hash ? location.hash.substring(1) : null;
    var firstError = Array.prototype.slice.call(document.querySelectorAll('.field-error')).find(function (s) { return s.textContent.trim(); });
    if (firstError && firstError.closest('[data-tab-panel]')) initial = firstError.closest('[data-tab-panel]').getAttribute('data-tab-panel');
    if (!initial) { try { initial = sessionStorage.getItem('tab:' + editor.getAttribute('data-editor-key')); } catch (e) { initial = null; } }
    activate(initial || 'definition', false);
    document.addEventListener('click', function (e) {
      var link = e.target.closest('[data-tab], [data-tab-link]');
      if (!link) return;
      e.preventDefault();
      activate(link.getAttribute('data-tab') || link.getAttribute('data-tab-link'));
    });
  }
  document.querySelectorAll('[data-goto-test]').forEach(function (button) {
    button.addEventListener('click', function () {
      if (tabLinks.length) activate('definition');
      var card = document.getElementById('test-card');
      if (!card) return;
      card.scrollIntoView({ behavior: 'smooth', block: 'center' });
      var user = card.querySelector('input[name=dbUser]');
      if (user) setTimeout(function () { user.focus(); }, 300);
    });
  });

  // ---------- Execution mode: show the hint and fields of the selected mode ----------
  function syncMode() {
    var checked = document.querySelector('input[name=executionMode]:checked');
    var mode = checked ? checked.value : 'CALLER';
    document.querySelectorAll('[data-mode-hint]').forEach(function (el) { el.classList.toggle('shown', el.getAttribute('data-mode-hint') === mode); });
    document.querySelectorAll('[data-mode-only]').forEach(function (el) { el.classList.toggle('shown', el.getAttribute('data-mode-only') === mode); });
  }
  document.querySelectorAll('input[name=executionMode]').forEach(function (r) { r.addEventListener('change', syncMode); });
  syncMode();

  // ---------- Unsaved changes and the save bar ----------
  var form = document.querySelector('form[data-dirty-form]');
  var bar = document.querySelector('[data-savebar]');
  var baseline = {};
  var submitting = false;
  var isNew = form && form.getAttribute('data-new') === 'true';
  function tracked(el) {
    if (!el.name || el.disabled || el.hasAttribute('data-nodirty') || el.type === 'submit' || el.type === 'button') return false;
    return !(el.name === 'dbUser' || el.name === 'dbPassword' || el.name.indexOf('tp_') === 0 || el.name.charAt(0) === '_');
  }
  function labelOf(el) {
    if (el.getAttribute('data-label')) return el.getAttribute('data-label');
    var group = el.closest('[data-dirty-group]');
    if (group) return group.getAttribute('data-dirty-group');
    return el.name;
  }
  function keyOf(el) { return el.type === 'radio' ? el.name : el.name + (el.type === 'checkbox' ? '#' + el.value : ''); }
  function valueOf(el) {
    if (el.type === 'radio') { var c = form.querySelector('input[type=radio][name="' + el.name + '"]:checked'); return c ? c.value : ''; }
    if (el.type === 'checkbox') return el.checked ? '1' : '0';
    if (el.name === 'sql') return el.value.replace(/\s+/g, ' ').trim(); // the server ignores whitespace-only changes
    return el.value;
  }
  function snapshot(root) {
    var state = {};
    Array.prototype.forEach.call((root || form).querySelectorAll('input, select, textarea'), function (el) {
      if (!tracked(el) || (root ? false : el.form !== form)) return;
      state[keyOf(el)] = { value: valueOf(el), label: labelOf(el), versioned: el.hasAttribute('data-versioned'),
        panel: el.closest('[data-tab-panel]') ? el.closest('[data-tab-panel]').getAttribute('data-tab-panel') : null,
        columns: !!el.closest('#columns') };
    });
    return state;
  }
  function changes() {
    var now = snapshot();
    var changed = [];
    Object.keys(now).forEach(function (k) { if (!baseline[k] || baseline[k].value !== now[k].value) changed.push(now[k]); });
    Object.keys(baseline).forEach(function (k) { if (!now[k]) changed.push(baseline[k]); });
    return changed;
  }
  function render() {
    if (!form || !bar) return;
    var changed = changes();
    var dirty = changed.length > 0;
    bar.classList.toggle('dirty', dirty);
    bar.classList.toggle('is-new', isNew);
    var labels = [];
    changed.forEach(function (c) { if (labels.indexOf(c.label) < 0) labels.push(c.label); });
    var title = bar.querySelector('[data-savebar-title]');
    var detail = bar.querySelector('[data-savebar-detail]');
    if (isNew) {
      title.textContent = 'New query';
    } else if (dirty) {
      title.textContent = 'Unsaved changes: ' + labels.slice(0, 5).join(', ') + (labels.length > 5 ? ' …' : '');
      var versioned = changed.some(function (c) { return c.versioned; });
      detail.textContent = versioned
        ? 'Saving creates version ' + detail.getAttribute('data-next-version') + ' and clears the cache of this query.'
        : 'Saving applies the settings without a new version.';
    } else {
      title.textContent = 'No unsaved changes';
      detail.textContent = 'Saving a changed SQL, datasource or parameters creates a new version and clears the cache of this query.';
    }
    var dirtyPanels = {};
    changed.forEach(function (c) { if (c.panel) dirtyPanels[c.panel] = true; });
    tabLinks.forEach(function (a) { a.classList.toggle('dirty', !!dirtyPanels[a.getAttribute('data-tab')]); });
    return dirty;
  }
  if (form) {
    baseline = snapshot();
    form.addEventListener('input', render);
    form.addEventListener('change', render);
    form.addEventListener('submit', function () { submitting = true; });
    window.addEventListener('beforeunload', function (e) {
      if (!submitting && changes().length) { e.preventDefault(); e.returnValue = ''; }
    });
    var discard = bar && bar.querySelector('[data-discard]');
    if (discard) discard.addEventListener('click', function () {
      if (confirm('Discard unsaved changes?')) { submitting = true; location.reload(); }
    });
    // After a new snapshot the column fields are fresh server state: re-baseline only those.
    document.body.addEventListener('htmx:afterSwap', function (e) {
      if (!e.detail.target || e.detail.target.id !== 'columns') return;
      Object.keys(baseline).forEach(function (k) { if (baseline[k].columns) delete baseline[k]; });
      var fresh = snapshot(e.detail.target);
      Object.keys(fresh).forEach(function (k) { baseline[k] = fresh[k]; });
      if (tabLinks.length) activate('spalten');
      render();
    });
    document.querySelectorAll('[data-show-tab]').forEach(function (b) {
      b.addEventListener('click', function () {
        var sql = baseline.sql;
        if (sql && sqlArea && sql.value !== valueOf(sqlArea)) toast('Note: the snapshot uses the saved SQL – save your changes first.', 'err');
      });
    });
    render();
  }

  // ---------- Generic repeatable rows: <table id="X" data-prefix="X" class="rows">, <template id="X-template">,
  // <button class="add-row" data-table="X">. Field names are renumbered as X[i].field. ----------
  document.querySelectorAll('table.rows[data-prefix]').forEach(function (rows) {
    var prefix = rows.getAttribute('data-prefix');
    var rowsTemplate = document.getElementById(prefix + '-template');
    var rowsBody = rows.querySelector('tbody');
    var add = document.querySelector('.add-row[data-table="' + prefix + '"]');
    if (!rowsTemplate || !rowsBody || !add) return;
    function renumberRows() {
      var list = rowsBody.querySelectorAll('tr.data-row');
      var re = new RegExp(prefix + '\\[(\\d+|__IDX__)\\]');
      list.forEach(function (row, i) {
        row.querySelectorAll('[name]').forEach(function (el) { el.name = el.name.replace(re, prefix + '[' + i + ']'); });
      });
      var emptyRow = rows.querySelector('tr.row-empty');
      if (emptyRow) emptyRow.style.display = list.length ? 'none' : '';
      rows.dispatchEvent(new Event('input', { bubbles: true }));
    }
    rowsBody.addEventListener('click', function (e) {
      if (e.target.classList.contains('remove-row')) { e.target.closest('tr').remove(); renumberRows(); }
    });
    add.addEventListener('click', function () {
      var count = rowsBody.querySelectorAll('tr.data-row').length;
      rowsBody.insertAdjacentHTML('beforeend', rowsTemplate.innerHTML.replace(/__IDX__/g, String(count)));
      renumberRows();
      var last = rowsBody.querySelector('tr.data-row:last-child .row-name');
      if (last) last.focus();
    });
    renumberRows();
  });

  // ---------- Query parameters ----------
  var table = document.getElementById('params');
  var template = document.getElementById('param-template');
  var addButton = document.getElementById('add-param');
  if (!table || !template || !addButton) return;
  var body = table.querySelector('tbody');

  function renumber() {
    var rows = body.querySelectorAll('tr.param-row');
    rows.forEach(function (row, i) {
      row.querySelectorAll('[name]').forEach(function (el) {
        el.name = el.name.replace(/parameters\[\d+\]|parameters\[__IDX__\]/, 'parameters[' + i + ']');
      });
      var nameInput = row.querySelector('.p-name');
      var testInput = row.querySelector('.p-test');
      if (nameInput && testInput) testInput.name = 'tp_' + nameInput.value;
    });
    var empty = table.querySelector('tr.param-empty');
    if (empty) empty.style.display = rows.length ? 'none' : '';
    table.dispatchEvent(new Event('input', { bubbles: true }));
  }

  body.addEventListener('input', function (e) {
    if (e.target.classList.contains('p-name')) {
      var testInput = e.target.closest('tr').querySelector('.p-test');
      if (testInput) testInput.name = 'tp_' + e.target.value;
    }
  });
  body.addEventListener('click', function (e) {
    if (e.target.classList.contains('remove-param')) {
      e.target.closest('tr').remove();
      renumber();
    }
  });
  addButton.addEventListener('click', function () {
    var count = body.querySelectorAll('tr.param-row').length;
    var html = template.innerHTML.replace(/__IDX__/g, String(count));
    body.insertAdjacentHTML('beforeend', html);
    renumber();
    var last = body.querySelector('tr.param-row:last-child .p-name');
    if (last) last.focus();
  });
  renumber();
})();
