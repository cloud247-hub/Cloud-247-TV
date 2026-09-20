# Cloud247 TV v1.0.1

Nettleserbasert IPTV-spiller for brukerens egne M3U/M3U8-spillelister.

## Funksjoner

- M3U/M3U8 via URL eller lokal fil
- Sikker Cloudflare Worker-proxy for URL-import av M3U og XMLTV
- Kanalgrupper fra `group-title`
- Logoer fra `tvg-logo`
- Søk og favoritter
- HLS-avspilling med hls.js 1.7.3 + native HLS fallback
- XMLTV/EPG via URL eller lokal fil
- Nå / neste og programprogresjon
- Norsk/engelsk grensesnitt
- Responsivt Cloud247-design
- Ingen permanent lagring av M3U-, XMLTV- eller innloggingsdetaljer

## Arkitektur

```text
tv.cloud247.no
      |
      | POST /v1/fetch { url, kind }
      v
tv-api.cloud247.no
Cloudflare Worker
      |
      | server-side fetch
      v
IPTV / XMLTV provider
```

Worker-proxyen brukes kun for tekstbasert M3U/XMLTV-import. Videostrømmer går fortsatt direkte fra IPTV-leverandøren til nettleseren.

## Hvorfor proxy

Nettlesere blokkerer ofte IPTV-playlist-URL-er på grunn av CORS eller mixed content. Worker-proxyen henter playlist/EPG server-side og returnerer den til `https://tv.cloud247.no` med riktige CORS-headere.

## Cloudflare Worker

Koden ligger i `worker/`.

### Deploy

Forutsetninger:

- `cloud247.no` ligger i Cloudflare-kontoen
- Node.js/npm er installert
- Wrangler er autentisert mot riktig Cloudflare-konto

```bash
cd worker
npm install
npx wrangler login
npm run check
npm run deploy
```

`worker/wrangler.toml` oppretter custom domain:

```text
tv-api.cloud247.no
```

Frontend bruker:

```text
https://tv-api.cloud247.no/v1/fetch
```

Etter deploy kan health-endepunktet testes:

```text
https://tv-api.cloud247.no/health
```

Forventet svar:

```json
{"ok":true,"service":"cloud247-tv-proxy","version":"1.0.1"}
```

## Proxy-sikkerhet

v1.0.1 har:

- Origin-lås til `https://tv.cloud247.no`
- kun `POST /v1/fetch` og `OPTIONS`
- kun HTTP/HTTPS som upstream
- blokkering av localhost/private IPv4 og IPv6-literals
- validering av redirects
- portbegrensning til 80, 443, 8080 og 8443
- 15 sekunders upstream-timeout
- maks 8 MiB M3U og 25 MiB XMLTV
- ingen caching av playlist/EPG
- ingen upstream-URL i querystring
- ingen logging av M3U-URL eller credentials i Worker-koden
- Basic Auth i URL flyttes til `Authorization` ved upstream-fetch

Origin-lås er ikke autentisering alene. Legg gjerne en Cloudflare Rate Limiting Rule på `tv-api.cloud247.no/v1/fetch` ved offentlig lansering.

## Personvern

- URL-import sendes til Cloudflare Worker kun for å hente innholdet.
- URL-en ligger i POST-body, ikke querystring.
- Worker lagrer ikke URL, playlist, EPG eller credentials.
- Lokale M3U/XMLTV-filer forlater ikke nettleseren.
- Playlist-URL-feltet tømmes etter vellykket import.
- Playlist-data beholdes kun i fanens minne.
- Favoritt-ID-er lagres lokalt i `localStorage`.

## Viktig om videostrømmer

v1.0.1 proxyer ikke HLS-manifester eller videosegmenter. Hvis selve kanalen senere blokkeres av CORS eller kun finnes på HTTP, kan playlist-importen fungere mens videoen fortsatt feiler. Det håndteres separat for å unngå å gjøre Worker til en åpen eller kostbar videoproxy.

## GitHub Pages

Frontend publiseres fra `main` / root og bruker `CNAME`:

```text
tv.cloud247.no
```

## Neste plattformsteg

Samme parser/datamodell kan brukes videre i Android TV-klient med Media3/ExoPlayer. Samsung Tizen-klienten bør bruke AVPlay for selve videostrømmen.
