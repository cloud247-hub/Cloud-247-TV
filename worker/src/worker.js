const DEFAULT_ORIGINS = ['https://tv.cloud247.no'];

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
      return json({ ok: true, service: 'cloud247-tv-proxy', version: '1.0.1' }, 200, {
        'Cache-Control': 'no-store',
      });
    }

    if (request.method !== 'POST' || new URL(request.url).pathname !== '/v1/fetch') {
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
      return json({ error: error.message || 'upstream_error' }, status, cors);
    }
  },
};

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
        'User-Agent': env.UPSTREAM_USER_AGENT || 'Cloud247-TV-Proxy/1.0.1',
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

function json(value, status = 200, extraHeaders = {}) {
  const headers = new Headers(extraHeaders);
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('Cache-Control', 'no-store');
  headers.set('X-Content-Type-Options', 'nosniff');
  return new Response(JSON.stringify(value), { status, headers });
}
