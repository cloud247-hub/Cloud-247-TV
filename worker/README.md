# Cloud247 TV Worker

## Sports Hub

Cloud247 TV Android v1.5.0 uses three sports data sources behind the Worker:

- Football: API-Football / API-Sports
- Tennis: Live Tennis API
- Golf: ESPN public golf endpoints
- XMLTV/M3U stays local to the Android app and is used only for channel matching.

### Required secrets

Create free API keys, then store them as Cloudflare Worker secrets:

```bash
cd worker
npx wrangler secret put API_FOOTBALL_KEY
npx wrangler secret put LIVE_TENNIS_API_KEY
npx wrangler deploy
```

The golf source does not require a key.

The Android app never receives the upstream API keys. It calls:

```
POST https://tv-api.cloud247.no/v1/sports/upcoming
```

Responses are normalized and upstream calls are cached in the Worker to conserve free-tier quotas.
