const DEFAULT_ORIGINS = ['https://tv.cloud247.no'];
const PAIR_TTL_MS = 10 * 60 * 1000;
const PAIR_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

const LIMITS = {
  playlist: 8 * 1024 * 1024,
  epg: 25 * 1024 * 1024,
};

const ALLOWED_TYPES = {
  playlist: [
    'application/vnd.apple.mpegurl',
    'application/x-mpegurl',
    'audio/mpegurl',
    'audio/x-mpegurl',
    'text/plain',
    'application/octet-stream',
  ],
  epg: [
    'application/xml',
    'text/xml',
    'text/plain',
    'application/octet-stream',
  ],
};

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin') || '';
    const allowedOrigins = parseOrigins(env.ALLOWED_ORIGINS);
    const cors = corsHeaders(origin, allowedOrigins);

    if (request.method === 'OPTIONS') {
      if (!isAllowedOrigin(origin, allowedOrigins)) {
        return json({ error: 'origin_not_allowed' }, 403, cors);
      }
      return new Response(null, { status: 204, headers: cors });
    }

    if (request.method === 'GET' && new URL(request.url).pathname === '/health') {
      const headers = new Headers(cors);
      headers.set('Cache-Control', 'no-store');
      return json({ ok: true, service: 'cloud247-tv-proxy', version: '1.1.0' }, 200, headers);
    }

    const requestUrl = new URL(request.url);

    if (request.method === 'POST' && requestUrl.pathname === '/v1/pair/create') {
      return createPairingSession(env);
    }

    if (request.method === 'POST' && requestUrl.pathname === '/v1/pair/poll') {
      return pollPairingSession(request, env);
    }

    if (request.method === 'POST' && requestUrl.pathname === '/v1/pair/submit') {
      if (!isAllowedOrigin(origin, allowedOrigins)) {
        return json({ error: 'origin_not_allowed' }, 403, cors);
      }
      return submitPairingSession(request, env, cors);
    }

    if (request.method !== 'POST' || requestUrl.pathname !== '/v1/fetch') {
      return json({ error: 'not_found' }, 404, cors);
    }

    if (!isAllowedOrigin(origin, allowedOrigins)) {
      return json({ error: 'origin_not_allowed' }, 403, cors);
    }

    if (!request.headers.get('Content-Type')?.toLowerCase().startsWith('application/json')) {
      return json({ error: 'content_type_required' }, 415, cors);
    }

    let input;
    try {
      input = await request.json();
    } catch {
      return json({ error: 'invalid_json' }, 400, cors);
    }

    const kind = input?.kind === 'epg' ? 'epg' : input?.kind === 'playlist' ? 'playlist' : null;
    if (!kind || typeof input?.url !== 'string' || input.url.length > 4096) {
      return json({ error: 'invalid_request' }, 400, cors);
    }

    let target;
    try {
      target = normalizeTarget(input.url);
    } catch (error) {
      return json({ error: error.message || 'invalid_url' }, 400, cors);
    }

    try {
      const upstream = await fetchValidated(target, kind, env);
      const contentType = (upstream.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
      const contentLength = Number(upstream.headers.get('Content-Length') || 0);
      const maxBytes = LIMITS[kind];

      if (contentLength && contentLength > maxBytes) {
        return json({ error: 'upstream_too_large' }, 413, cors);
      }

      if (contentType && !ALLOWED_TYPES[kind].includes(contentType) && !contentType.startsWith('text/')) {
        return json({ error: 'unsupported_upstream_type' }, 415, cors);
      }

      const body = await readLimited(upstream.body, maxBytes);
      const headers = new Headers(cors);
      headers.set('Cache-Control', 'no-store, private');
      headers.set('Content-Type', kind === 'epg' ? 'application/xml; charset=utf-8' : 'text/plain; charset=utf-8');
      headers.set('X-Content-Type-Options', 'nosniff');
      headers.set('Referrer-Policy', 'no-referrer');
      headers.set('Cross-Origin-Resource-Policy', 'cross-origin');
      return new Response(body, { status: 200, headers });
    } catch (error) {
      const status = Number(error.status) || 502;
      return json({
        error: error.message || 'upstream_error',
        detail: error.detail || undefined,
        upstream_status: error.upstreamStatus || undefined,
      }, status, cors);
    }
  },
};


async function createPairingSession(env) {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const code = randomPairCode();
    const token = randomToken();
    const expiresAt = Date.now() + PAIR_TTL_MS;
    const stub = env.PAIRING.get(env.PAIRING.idFromName(code));
    const response = await stub.fetch('https://pair.internal/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, expiresAt }),
    });

    if (response.status === 409) continue;
    if (!response.ok) return json({ error: 'pair_create_failed' }, 502);

    return json({
      code,
      token,
      link: `https://tv.cloud247.no/link/?code=${code}`,
      expires_in: Math.floor(PAIR_TTL_MS / 1000),
    }, 201);
  }

  return json({ error: 'pair_code_unavailable' }, 503);
}

async function pollPairingSession(request, env) {
  const input = await readJson(request);
  const code = normalizePairCode(input?.code);
  const token = typeof input?.token === 'string' ? input.token : '';

  if (!code || token.length < 32) {
    return json({ error: 'invalid_pair_request' }, 400);
  }

  const stub = env.PAIRING.get(env.PAIRING.idFromName(code));
  const response = await stub.fetch('https://pair.internal/poll', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token }),
  });

  if (response.status === 204) return new Response(null, { status: 204 });
  const body = await response.text();
  return new Response(body, {
    status: response.status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

async function submitPairingSession(request, env, cors) {
  const input = await readJson(request);
  const code = normalizePairCode(input?.code);
  const playlistUrl = typeof input?.url === 'string' ? input.url.trim() : '';

  if (!code || playlistUrl.length < 8 || playlistUrl.length > 4096) {
    return json({ error: 'invalid_pair_request' }, 400, cors);
  }

  let parsed;
  try {
    parsed = new URL(playlistUrl);
  } catch {
    return json({ error: 'invalid_url' }, 400, cors);
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    return json({ error: 'scheme_not_allowed' }, 400, cors);
  }

  const stub = env.PAIRING.get(env.PAIRING.idFromName(code));
  const response = await stub.fetch('https://pair.internal/submit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url: playlistUrl }),
  });

  if (response.status === 204) {
    return new Response(null, { status: 204, headers: cors });
  }

  const payload = await response.text();
  const headers = new Headers(cors);
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('Cache-Control', 'no-store');
  return new Response(payload, { status: response.status, headers });
}

function randomPairCode() {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  let out = '';
  for (const value of bytes) out += PAIR_CODE_ALPHABET[value % PAIR_CODE_ALPHABET.length];
  return out;
}

function randomToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function normalizePairCode(value) {
  if (typeof value !== 'string') return '';
  const code = value.toUpperCase().replace(/[^A-Z0-9]/g, '');
  return /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/.test(code) ? code : '';
}

async function readJson(request) {
  if (!request.headers.get('Content-Type')?.toLowerCase().startsWith('application/json')) return null;
  try {
    return await request.json();
  } catch {
    return null;
  }
}

export class PairingSession {
  constructor(state) {
    this.state = state;
  }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    const input = await readJson(request);
    const now = Date.now();

    if (path === '/create') {
      const existingExpiry = Number(await this.state.storage.get('expiresAt') || 0);
      if (existingExpiry > now) return json({ error: 'pair_exists' }, 409);

      await this.state.storage.deleteAll();
      const expiresAt = Number(input?.expiresAt || 0);
      const token = typeof input?.token === 'string' ? input.token : '';
      if (!token || expiresAt <= now) return json({ error: 'invalid_pair_session' }, 400);

      await this.state.storage.put({ token, expiresAt });
      await this.state.storage.setAlarm(expiresAt);
      return json({ ok: true }, 201);
    }

    const expiresAt = Number(await this.state.storage.get('expiresAt') || 0);
    if (!expiresAt || expiresAt <= now) {
      await this.state.storage.deleteAll();
      return json({ error: 'pair_expired' }, 410);
    }

    if (path === '/submit') {
      const url = typeof input?.url === 'string' ? input.url : '';
      if (!url) return json({ error: 'invalid_url' }, 400);
      await this.state.storage.put('playlistUrl', url);
      return new Response(null, { status: 204 });
    }

    if (path === '/poll') {
      const expectedToken = String(await this.state.storage.get('token') || '');
      const token = typeof input?.token === 'string' ? input.token : '';
      if (!expectedToken || token !== expectedToken) return json({ error: 'invalid_pair_token' }, 403);

      const playlistUrl = await this.state.storage.get('playlistUrl');
      if (!playlistUrl) return new Response(null, { status: 204 });

      await this.state.storage.deleteAll();
      try { await this.state.storage.deleteAlarm(); } catch {}
      return json({ url: playlistUrl }, 200);
    }

    return json({ error: 'not_found' }, 404);
  }

  async alarm() {
    await this.state.storage.deleteAll();
  }
}

function parseOrigins(value) {
  const items = (value || DEFAULT_ORIGINS.join(','))
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);
  return items.length ? items : DEFAULT_ORIGINS;
}

function isAllowedOrigin(origin, allowedOrigins) {
  return Boolean(origin && allowedOrigins.includes(origin));
}

function corsHeaders(origin, allowedOrigins) {
  const headers = new Headers({
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Max-Age': '86400',
    'Vary': 'Origin',
  });
  if (isAllowedOrigin(origin, allowedOrigins)) {
    headers.set('Access-Control-Allow-Origin', origin);
  }
  return headers;
}

function normalizeTarget(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error('invalid_url');
  }

  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('scheme_not_allowed');
  if (url.username || url.password) {
    // Credentials in URL authority are accepted, but never logged or echoed.
  }
  if (!url.hostname || url.hostname.length > 253) throw new Error('invalid_host');
  if (isBlockedHost(url.hostname)) throw new Error('blocked_host');

  const port = url.port ? Number(url.port) : null;
  if (port && ![80, 443, 8080, 8443].includes(port)) throw new Error('port_not_allowed');

  url.hash = '';
  return url;
}

async function fetchValidated(initialUrl, kind, env) {
  let current = new URL(initialUrl);
  const maxRedirects = 4;

  for (let i = 0; i <= maxRedirects; i += 1) {
    if (isBlockedHost(current.hostname)) {
      const error = new Error('blocked_host');
      error.status = 400;
      throw error;
    }

    const controller = new AbortController();
    const timeoutMs = Number(env.UPSTREAM_TIMEOUT_MS || 15000);
    const timer = setTimeout(() => controller.abort(), Math.min(Math.max(timeoutMs, 3000), 30000));

    let response;
    try {
      const upstreamHeaders = new Headers({
        'Accept': kind === 'epg'
          ? 'application/xml,text/xml,text/plain;q=0.9,*/*;q=0.5'
          : 'application/vnd.apple.mpegurl,application/x-mpegurl,text/plain;q=0.9,*/*;q=0.5',
        'User-Agent': env.UPSTREAM_USER_AGENT || 'Cloud247-TV-Proxy/1.1.0',
      });
      const fetchUrl = new URL(current);
      if (fetchUrl.username || fetchUrl.password) {
        const user = decodeURIComponent(fetchUrl.username);
        const pass = decodeURIComponent(fetchUrl.password);
        upstreamHeaders.set('Authorization', 'Basic ' + btoa(user + ':' + pass));
        fetchUrl.username = '';
        fetchUrl.password = '';
      }

      response = await fetch(fetchUrl.toString(), {
        method: 'GET',
        redirect: 'manual',
        signal: controller.signal,
        headers: upstreamHeaders,
        cf: {
          cacheTtl: 0,
          cacheEverything: false,
        },
      });
    } catch (error) {
      const wrapped = new Error(error?.name === 'AbortError' ? 'upstream_timeout' : 'upstream_fetch_failed');
      wrapped.status = error?.name === 'AbortError' ? 504 : 502;
      throw wrapped;
    } finally {
      clearTimeout(timer);
    }

    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('Location');
      if (!location || i === maxRedirects) {
        const error = new Error('redirect_failed');
        error.status = 502;
        throw error;
      }
      const next = new URL(location, current);
      if (!['http:', 'https:'].includes(next.protocol) || isBlockedHost(next.hostname)) {
        const error = new Error('redirect_blocked');
        error.status = 400;
        throw error;
      }
      current = next;
      continue;
    }

    if (!response.ok) {
      const error = new Error('upstream_http_' + response.status);
      error.status = response.status >= 400 && response.status < 500 ? 502 : response.status;
      error.upstreamStatus = response.status;
      error.detail = await readErrorDetail(response);
      throw error;
    }

    return response;
  }

  const error = new Error('too_many_redirects');
  error.status = 502;
  throw error;
}

function isBlockedHost(hostname) {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();

  if (
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host.endsWith('.local') ||
    host.endsWith('.internal') ||
    host.endsWith('.home') ||
    host === 'metadata.google.internal' ||
    host === 'instance-data.ec2.internal'
  ) return true;

  if (host === '0.0.0.0' || host === '::' || host === '::1') return true;
  if (isPrivateIPv4(host)) return true;
  // v1.0.1 blocks IPv6 literals entirely. Hostnames with public IPv6 remain usable.
  if (host.includes(':')) return true;

  return false;
}

function isPrivateIPv4(host) {
  if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) return false;
  const parts = host.split('.').map(Number);
  if (parts.some((n) => n < 0 || n > 255)) return true;

  const [a, b] = parts;
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19)) ||
    a >= 224
  );
}

function isPrivateIPv6(host) {
  if (!host.includes(':')) return false;
  const h = host.toLowerCase();
  return (
    h === '::1' ||
    h === '::' ||
    h.startsWith('fc') ||
    h.startsWith('fd') ||
    /^fe[89ab]/.test(h) ||
    h.startsWith('ff') ||
    h.startsWith('2001:db8:')
  );
}

async function readLimited(stream, maxBytes) {
  if (!stream) return new Uint8Array();
  const reader = stream.getReader();
  const chunks = [];
  let total = 0;

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel('response too large');
        const error = new Error('upstream_too_large');
        error.status = 413;
        throw error;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

async function readErrorDetail(response) {
  try {
    const contentType = (response.headers.get('Content-Type') || '').toLowerCase();
    if (!(contentType.includes('text') || contentType.includes('json') || contentType.includes('html') || contentType.includes('xml') || !contentType)) {
      return '';
    }

    const reader = response.body?.getReader();
    if (!reader) return '';

    let total = 0;
    const chunks = [];
    while (total < 2048) {
      const { value, done } = await reader.read();
      if (done) break;
      const remaining = 2048 - total;
      const part = value.byteLength > remaining ? value.slice(0, remaining) : value;
      chunks.push(part);
      total += part.byteLength;
      if (total >= 2048) {
        try { await reader.cancel(); } catch {}
        break;
      }
    }

    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }

    let text = new TextDecoder('utf-8', { fatal: false }).decode(bytes);
    text = text
      .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ')
      .replace(/<[^>]+>/g, ' ')
      .replace(/https?:\/\/\S+/gi, '[url]')
      .replace(/(username|user|password|pass|token|key)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')
      .replace(/[A-Za-z0-9_-]{40,}/g, '[redacted]')
      .replace(/\s+/g, ' ')
      .trim();

    return text.slice(0, 300);
  } catch {
    return '';
  }
}

function json(value, status = 200, extraHeaders = {}) {
  const headers = new Headers(extraHeaders);
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('Cache-Control', 'no-store');
  headers.set('X-Content-Type-Options', 'nosniff');
  return new Response(JSON.stringify(value), { status, headers });
}
