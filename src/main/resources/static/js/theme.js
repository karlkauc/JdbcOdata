// Applies the stored light/dark choice before the first paint (no inline script: the admin UI sends a CSP).
try {
  var t = localStorage.getItem('jdbcodata-theme');
  if (t === 'dark' || t === 'light') document.documentElement.setAttribute('data-theme', t);
} catch (e) { /* storage blocked: follow the OS */ }
