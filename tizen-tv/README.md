# Cloud247 TV for Samsung Tizen

Samsung TV-versjonen av Cloud247 TV.

## v1.0.1

Denne versjonen er oppdatert for den nye **Tizen Extension for Visual Studio Code**.

Endringer fra v1.0.0:

- Ny `Setup-Tizen.ps1` setter `tizen.v2.working.project` automatisk til korrekt absolutt prosjektsti.
- Ny `Open-Cloud247-Tizen.cmd` for ett-klikk-oppsett på Windows.
- Varsler hvis prosjektstien inneholder tegn Tizen Extension ikke godtar, for eksempel `Cloud-247-TV-main (2)`.
- VS Code anbefaler automatisk den offisielle `tizen.vscode-tizen-csharp`-extensionen.
- Dokumentasjonen er oppdatert fra gammel Tizen Studio-flyt til dagens VS Code-flyt.
- Appversjon er økt til `1.0.1`.

## Funksjoner

- QR-pairing via `tv.cloud247.no/link`
- M3U/M3U8 lastes direkte fra TV-en
- Samsung AVPlay for selve videostrømmen
- 2-kolonne TV-grensesnitt
- OK åpner valgt kanal fullscreen
- Hold OK legger til/fjerner favoritt
- Alle kanaler
- Favoritter
- Norske kanaler (smartgruppe)
- Fotball (smartgruppe for kanalnavn som starter med `EPL`)
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

## Forutsetninger

Du trenger:

1. Visual Studio Code.
2. Den offisielle **Tizen Extension**: `tizen.vscode-tizen-csharp`.
3. Et **Samsung Certificate** med TV-ens DUID.
4. Samsung-TV-en i Developer Mode.
5. PC og TV på samme nettverk under installasjon/testing.

Den nye Tizen Extension administrerer SDK-komponentene selv. Tizen Studio er ikke nødvendig for denne arbeidsflyten.

## Viktig om prosjektsti

Ikke legg prosjektet i en mappe med parenteser eller andre spesialtegn som Tizen Extension avviser.

Unngå for eksempel:

```text
C:\Users\Sebastian\Downloads\Cloud-247-TV-main (2)\...
```

Bruk heller:

```text
C:\Dev\Cloud247-TV-Tizen\tizen-tv
```

## Rask oppstart på Windows

Pakk ut eller klon prosjektet til en ren sti, og åpne `tizen-tv`.

Kjør deretter:

```powershell
.\Setup-Tizen.ps1
```

eller dobbeltklikk:

```text
Open-Cloud247-Tizen.cmd
```

Setup-scriptet oppretter lokalt:

```text
.vscode\settings.json
```

med korrekt absolutt verdi for:

```json
{
  "tizen.v2.working.project": "C:\\...\\tizen-tv"
}
```

Dette løser feilen:

```text
Failed to execute build-project: Working project is not set
```

## Bygg og installer fra VS Code

Når prosjektet er åpnet:

1. Kjør **Developer: Reload Window**.
2. Kontroller under **Active Targets** at:
   - prosjektet er aktivt
   - Samsung-TV-en er tilkoblet
   - `Cloud247TV` er aktivt certificate profile
3. Velg **Build Project**.
4. Når build er vellykket, velg **Run Project**.

Tizen Extension bygger, signerer med det aktive Samsung-sertifikatet, installerer WGT-en og starter appen på TV-en.

## Developer Mode på Samsung-TV

1. Åpne **Apps**.
2. Åpne **App Settings**.
3. Tast **12345**.
4. Slå på **Developer mode**.
5. Skriv inn IP-adressen til PC-en.
6. Start TV-en helt på nytt.

## Nettverksfeil ved SDK/sertifikatoppsett

Tizen Extension må kunne nå blant annet:

```text
https://download.tizen.org/
```

Hvis Package Manager henger eller loggen viser `ETIMEDOUT`, test fra et annet nettverk/mobil hotspot. Når SDK-komponentene og sertifikatet er ferdig satt opp, kan du normalt gå tilbake til samme LAN som TV-en for deploy.

## Pairing API

Tizen v1.0.1 krever Cloud247 TV Worker v1.2.0 eller nyere. Worker støtter `X-Cloud247-TV-Client` for `/v1/pair/create` og `/v1/pair/poll`.

Mobilens `/v1/pair/submit` er fortsatt låst til `https://tv.cloud247.no`.
