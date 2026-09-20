# Cloud247 TV for Samsung Tizen

Samsung TV-versjonen av Cloud247 TV.

## v1.0.0

Funksjoner:

- QR-pairing via `tv.cloud247.no/link`
- M3U/M3U8 lastes direkte fra TV-en
- Samsung AVPlay for selve videostrømmen
- 2-kolonne TV-grensesnitt
- OK åpner valgt kanal fullscreen
- Hold OK legger til/fjerner favoritt
- Alle kanaler
- Favoritter
- Norske kanaler (smartgruppe)
- Premier League (kanalnavn som starter med `EPL`)
- vanlige M3U-grupper
- virtualisert kanalliste for store spillelister
- M3U-grense 64 MiB
- URL lagres i Tizen KeyManager når tilgjengelig
- fullscreen-overlay skjules automatisk etter 2,5 sekunder

## Arkitektur

```text
Mobil
  |
  | tv.cloud247.no/link
  v
Cloudflare pairing API
  |
  | M3U URL (kortvarig)
  v
Samsung TV / Tizen
  |
  +--> M3U hentes direkte fra IPTV-leverandøren
  |
  +--> kanal spilles direkte med Samsung AVPlay
```

Cloudflare brukes kun til å overføre M3U-adressen under pairing. Den brukes ikke som video-proxy.

## Før du tester på TV

Installer:

1. Tizen Studio
2. Samsung TV Extensions
3. Samsung Certificate Extension

Alle Tizen TV-applikasjoner må signeres med gyldig Samsung/Tizen-sertifikat før de kan installeres på en fysisk TV.

## Import i Tizen Studio

- File > Import
- Tizen > Tizen Project
- Velg mappen `tizen-tv`
- Velg Samsung TV-profil hvis Tizen Studio spør

## Aktivere Developer Mode på TV

På Samsung TV:

1. Åpne **Apps**
2. Åpne **App Settings**
3. Tast **12345**
4. Slå på **Developer mode**
5. Skriv inn IP-adressen til PC-en
6. Start TV-en på nytt

PC og TV må være på samme nettverk.

Deretter kobler du til TV-en fra **Tools > Device Manager** i Tizen Studio.

## Bygg og installer

En installérbar `.wgt` må signeres med ditt Samsung certificate profile.

Fra Tizen Studio er enkleste vei:

- høyreklikk prosjektet
- **Run As > Tizen Web Application**

Tizen Studio bygger, signerer og installerer appen på den tilkoblede TV-en.

CLI kan også brukes etter at certificate profile er opprettet:

```text
tizen build-web --output .build tizen-tv
tizen package -t wgt -s <CERTIFICATE_PROFILE> -- tizen-tv/.build
```

## Pairing API

Tizen v1.0.0 krever Cloud247 TV Worker v1.2.0 eller nyere. Worker støtter en begrenset `X-Cloud247-TV-Client` header kun for `/v1/pair/create` og `/v1/pair/poll`.

Mobilens `/v1/pair/submit` er fortsatt låst til `https://tv.cloud247.no`.
