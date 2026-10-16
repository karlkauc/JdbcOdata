(function () {
  var sqlArea = document.getElementById('sql');
  if (sqlArea && window.CodeMirror) {
    var cm = CodeMirror.fromTextArea(sqlArea, { mode: 'text/x-sql', theme: 'eclipse', lineNumbers: true, viewportMargin: Infinity });
    cm.on('change', function () { cm.save(); });
  }

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
  });
})();
