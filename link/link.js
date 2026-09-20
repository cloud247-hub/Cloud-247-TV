(() => {
  'use strict';

  const API = 'https://tv-api.cloud247.no/v1/pair/submit';
  const form = document.getElementById('pairForm');
  const code = document.getElementById('pairCode');
  const url = document.getElementById('playlistUrl');
  const button = document.getElementById('submitButton');
  const status = document.getElementById('status');

  const fromQuery = new URLSearchParams(location.search).get('code') || '';
  const cleanCode = normalizeCode(fromQuery);
  if (cleanCode) {
    code.value = cleanCode;
    url.focus();
  } else {
    code.focus();
  }

  code.addEventListener('input', () => {
    code.value = normalizeCode(code.value);
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const pairCode = normalizeCode(code.value);
    const playlistUrl = url.value.trim();

    if (!/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(pairCode)) {
      show('Skriv inn den seks tegn lange koden fra TV-en.', false);
      code.focus();
      return;
    }

    let parsed;
    try {
      parsed = new URL(playlistUrl);
    } catch {
      show('M3U-adressen er ikke gyldig.', false);
      url.focus();
      return;
    }
    if (!['http:', 'https:'].includes(parsed.protocol)) {
      show('Kun http:// og https:// støttes.', false);
      url.focus();
      return;
    }

    button.disabled = true;
    show('Sender til TV-en …', null);

    try {
      const response = await fetch(API, {
        method: 'POST',
        credentials: 'omit',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: pairCode, url: playlistUrl })
      });

      if (response.status === 204) {
        url.value = '';
        show('Ferdig! TV-en kobler seg til spillelisten nå.', true);
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
    }
  });

  function normalizeCode(value) {
    return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
  }

  function show(message, ok) {
    status.textContent = message;
    status.className = 'status' + (ok === true ? ' ok' : ok === false ? ' error' : '');
  }
})();
