# Cloud247 TV v1.0.0

Statisk IPTV-spiller for brukerens egne M3U/M3U8-spillelister.

## Funksjoner

- M3U/M3U8 via URL eller lokal fil
- Kanalgrupper fra `group-title`
- Logoer fra `tvg-logo`
- Søk og favoritter
- HLS-avspilling med hls.js 1.7.3 + native HLS fallback
- XMLTV/EPG via URL eller lokal fil
- Nå / neste og programprogresjon
- Norsk/engelsk grensesnitt
- Responsivt Cloud247-design
- Ingen backend og ingen lagring av M3U- eller EPG-URL-er

## Publisering

Legg innholdet i denne mappen på GitHub Pages eller Cloudflare Pages. Ingen build-prosess er nødvendig.

> Merk: M3U- og videokilder må tillate avspilling fra nettleser. CORS, mixed content (HTTP fra HTTPS) og codecs kan gjøre at en kanal fungerer i native TV-app, men ikke i webversjonen.

## Sikkerhet/personvern

- Playlist-URL tømmes fra feltet etter vellykket import.
- Playlist-data beholdes kun i fanens minne.
- Favoritt-ID-er lagres lokalt i `localStorage`.
- Eksterne kanallogoer lastes direkte fra URL-en som ligger i spillelisten.

## Neste plattformsteg

Samme parser/datamodell kan brukes videre i Android TV-klient med Media3/ExoPlayer. Samsung Tizen-klienten bør bruke AVPlay for selve videostrømmen.
