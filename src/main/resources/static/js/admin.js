(function () {
  var sqlArea = document.getElementById('sql');
  if (sqlArea && window.CodeMirror) {
    var cm = CodeMirror.fromTextArea(sqlArea, { mode: 'text/x-sql', theme: 'eclipse', lineNumbers: true, viewportMargin: Infinity });
    cm.on('change', function () { cm.save(); });
  }

  // Copy buttons: data-copy="#elementId" copies that element's text and gives short feedback.
  document.querySelectorAll('[data-copy]').forEach(function (button) {
    button.addEventListener('click', function () {
      var source = document.querySelector(button.getAttribute('data-copy'));
      if (!source || !navigator.clipboard) return;
      var original = button.textContent;
      navigator.clipboard.writeText(source.textContent.trim()).then(function () {
        button.textContent = 'Kopiert';
        setTimeout(function () { button.textContent = original; }, 1500);
      });
    });
  });

  // Generic repeatable rows: <table id="X" data-prefix="X" class="rows">, <template id="X-template">,
  // <button class="add-row" data-table="X">. Field names are renumbered as X[i].field.
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
