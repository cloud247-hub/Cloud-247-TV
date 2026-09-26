(() => {
  'use strict';

  const API = 'https://tv-api.cloud247.no/v1/pair/submit';
  const form = document.getElementById('pairForm');
  const code = document.getElementById('pairCode');
  const playlistUrl = document.getElementById('playlistUrl');
  const xcServer = document.getElementById('xcServer');
  const xcUsername = document.getElementById('xcUsername');
  const xcPassword = document.getElementById('xcPassword');
  const m3uPanel = document.getElementById('m3uPanel');
  const xtreamPanel = document.getElementById('xtreamPanel');
  const modeM3u = document.getElementById('modeM3u');
  const modeXtream = document.getElementById('modeXtream');
  const togglePassword = document.getElementById('togglePassword');
  const submitLabel = document.getElementById('submitLabel');
  const button = document.getElementById('submitButton');
  const status = document.getElementById('status');

  let mode = 'm3u';

  const params = new URLSearchParams(location.search);
  const cleanCode = normalizeCode(params.get('code') || '');
  const requestedMode = String(params.get('mode') || '').toLowerCase();

  if (cleanCode) code.value = cleanCode;
  setMode(requestedMode === 'xc' || requestedMode === 'xtream' ? 'xc' : 'm3u', false);

  if (cleanCode) {
    focusSource();
  } else {
    code.focus();
  }

  code.addEventListener('input', () => {
    code.value = normalizeCode(code.value);
  });

  modeM3u.addEventListener('click', () => setMode('m3u'));
  modeXtream.addEventListener('click', () => setMode('xc'));

  togglePassword.addEventListener('click', () => {
    const reveal = xcPassword.type === 'password';
    xcPassword.type = reveal ? 'text' : 'password';
    togglePassword.textContent = reveal ? 'Skjul' : 'Vis';
    togglePassword.setAttribute('aria-label', reveal ? 'Skjul passord' : 'Vis passord');
    togglePassword.setAttribute('aria-pressed', String(reveal));
    xcPassword.focus();
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearStatus();

    const pairCode = normalizeCode(code.value);
    if (!/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(pairCode)) {
      show('Skriv inn den seks tegn lange koden fra TV-en.', false);
      code.focus();
      return;
    }

    let targetUrl;
    if (mode === 'xc') {
      const result = buildXtreamUrl(
        xcServer.value.trim(),
        xcUsername.value.trim(),
        xcPassword.value
      );
      if (!result.ok) {
        show(result.message, false);
        if (result.field) result.field.focus();
        return;
      }
      targetUrl = result.url;
    } else {
      const result = validateHttpUrl(playlistUrl.value.trim());
      if (!result.ok) {
        show(result.message, false);
        playlistUrl.focus();
        return;
      }
      targetUrl = result.url;
    }

    button.disabled = true;
    modeM3u.disabled = true;
    modeXtream.disabled = true;
    show(mode === 'xc' ? 'Sender XC-kontoen sikkert til TV-en …' : 'Sender M3U til TV-en …', null);

    try {
      const response = await fetch(API, {
        method: 'POST',
        credentials: 'omit',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: pairCode, url: targetUrl })
      });

      if (response.status === 204) {
        if (mode === 'xc') {
          xcServer.value = '';
          xcUsername.value = '';
          xcPassword.value = '';
          xcPassword.type = 'password';
          togglePassword.textContent = 'Vis';
          togglePassword.setAttribute('aria-pressed', 'false');
          show('Ferdig! XC-kontoen er sendt til TV-en. Cloud247 TV kobler til og setter opp EPG automatisk på Android.', true);
        } else {
          playlistUrl.value = '';
          show('Ferdig! TV-en kobler seg til spillelisten nå.', true);
        }
        return;
      }

      let payload = {};
      try { payload = await response.json(); } catch {}

      const message = payload.error === 'pair_expired' || response.status === 410
        ? 'Koden har utløpt. Lag en ny kode på TV-en.'
        : payload.error === 'not_found' || response.status === 404
          ? 'Fant ikke denne TV-koden. Kontroller koden eller lag en ny.'
          : 'Kunne ikke koble til TV-en. Prøv igjen.';
      show(message, false);
    } catch {
      show('Får ikke kontakt med Cloud247 TV-tjenesten akkurat nå.', false);
    } finally {
      button.disabled = false;
      modeM3u.disabled = false;
      modeXtream.disabled = false;
    }
  });

  function setMode(nextMode, focus = true) {
    mode = nextMode === 'xc' ? 'xc' : 'm3u';
    const isXc = mode === 'xc';

    m3uPanel.hidden = isXc;
    xtreamPanel.hidden = !isXc;

    modeM3u.classList.toggle('is-active', !isXc);
    modeXtream.classList.toggle('is-active', isXc);
    modeM3u.setAttribute('aria-pressed', String(!isXc));
    modeXtream.setAttribute('aria-pressed', String(isXc));

    submitLabel.textContent = isXc ? 'Send XC til TV' : 'Send M3U til TV';
    clearStatus();
    if (focus) focusSource();
  }

  function focusSource() {
    if (mode === 'xc') xcServer.focus();
    else playlistUrl.focus();
  }

  function buildXtreamUrl(serverValue, username, password) {
    if (!serverValue) {
      return { ok: false, message: 'Skriv inn XC-serveren.', field: xcServer };
    }
    if (!username) {
      return { ok: false, message: 'Skriv inn XC-brukernavn.', field: xcUsername };
    }
    if (!password) {
      return { ok: false, message: 'Skriv inn XC-passord.', field: xcPassword };
    }

    let server = serverValue;
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(server)) {
      server = 'http://' + server;
    }

    let parsed;
    try {
      parsed = new URL(server);
    } catch {
      return { ok: false, message: 'XC-serveradressen er ikke gyldig.', field: xcServer };
    }

    if (!['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname) {
      return { ok: false, message: 'XC-serveren må bruke http:// eller https://.', field: xcServer };
    }

    let path = parsed.pathname.replace(/\/+$/, '');
    if (/\/(player_api\.php|get\.php|xmltv\.php)$/i.test(path)) {
      path = path.replace(/\/[^/]+$/, '');
    }

    const base = parsed.origin + (path && path !== '/' ? path : '');
    const query = new URLSearchParams({
      username,
      password,
      type: 'm3u_plus',
      output: 'ts'
    });

    return {
      ok: true,
      url: base.replace(/\/$/, '') + '/get.php?' + query.toString()
    };
  }

  function validateHttpUrl(value) {
    let parsed;
    try {
      parsed = new URL(value);
    } catch {
      return { ok: false, message: 'M3U-adressen er ikke gyldig.' };
    }

    if (!['http:', 'https:'].includes(parsed.protocol)) {
      return { ok: false, message: 'Kun http:// og https:// støttes.' };
    }

    return { ok: true, url: value };
  }

  function normalizeCode(value) {
    return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
  }

  function clearStatus() {
    status.textContent = '';
    status.className = 'status';
  }

  function show(message, ok) {
    status.textContent = message;
    status.className = 'status' + (ok === true ? ' ok' : ok === false ? ' error' : '');
  }
})();
