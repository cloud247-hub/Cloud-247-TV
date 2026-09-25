(() => {
  'use strict';

  const state = {
    lang: localStorage.getItem('cloud247tv:lang') || 'no'
  };

  const i18n = {
    no: {
      navAndroid: 'Android TV',
      navSamsung: 'Samsung TV',
      navPair: 'Koble TV',
      nativeApp: 'ANDROID TV · v1.1.8 · SAMSUNG TV · v1.0.3',
      eyebrowNew: 'DIN TV · DIN SPILLELISTE',
      heroNew1: 'TV-en din.',
      heroNew2: 'Spillelisten din.',
      leadNew: 'Cloud247 TV gir deg et ryddig TV-grensesnitt for din egen M3U-liste på Android TV og Samsung TV – med QR-pairing, favoritter, grupper og direkte avspilling fra TV-en.',
      pairTv: 'Koble TV',
      downloadAndroid: 'Android TV',
      downloadSamsung: 'Samsung TV',
      pointDirect: 'Direkte avspilling på TV-en',
      pointQr: 'QR-pairing på få sekunder',
      pointLocal: 'Ingen videoproxy',
      previewSub: 'DIN TV · DIN SPILLELISTE',
      channelsWord: 'kanaler',
      replace: 'Bytt liste',
      groups: 'GRUPPER',
      previewGroupAll: 'Alle kanaler',
      previewGroup1: 'Gruppe 1',
      previewGroup2: 'Gruppe 2',
      previewGroup3: 'Gruppe 3',
      searchPlaceholder: 'Søk kanaler…',
      previewChannel1: 'Kanal 1',
      previewChannel2: 'Kanal 2',
      previewChannel3: 'Kanal 3',
      previewPlay: 'åpner kanal',
      previewFav: 'Hold OK for favoritt',
      benefit1Title: 'Koble på mobilen',
      benefit1Text: 'Skann QR-koden på TV-en, lim inn M3U-adressen på mobilen og fortsett med fjernkontrollen.',
      benefit2Title: 'Spill direkte',
      benefit2Text: 'TV-en kobler seg direkte til leverandøren. Ingen nettleser-CORS og ingen videostrøm gjennom Cloud247.',
      benefit3Title: 'Finn kanalene raskt',
      benefit3Text: 'Favoritter, søk og oversiktlige grupper gjør store spillelister raske og enkle å navigere. På nettbrett kan du også bytte kanal med touch og swipe i fullskjerm.'
    },
    en: {
      navAndroid: 'Android TV',
      navSamsung: 'Samsung TV',
      navPair: 'Pair TV',
      nativeApp: 'ANDROID TV · v1.1.8 · SAMSUNG TV · v1.0.3',
      eyebrowNew: 'YOUR TV · YOUR PLAYLIST',
      heroNew1: 'Your TV.',
      heroNew2: 'Your playlist.',
      leadNew: 'Cloud247 TV gives you a clean TV interface for your own M3U playlist on Android TV and Samsung TV, with QR pairing, favourites, groups and direct playback on the TV.',
      pairTv: 'Pair TV',
      downloadAndroid: 'Android TV',
      downloadSamsung: 'Samsung TV',
      pointDirect: 'Direct playback on the TV',
      pointQr: 'QR pairing in seconds',
      pointLocal: 'No video proxy',
      previewSub: 'YOUR TV · YOUR PLAYLIST',
      channelsWord: 'channels',
      replace: 'Replace list',
      groups: 'GROUPS',
      previewGroupAll: 'All channels',
      previewGroup1: 'Group 1',
      previewGroup2: 'Group 2',
      previewGroup3: 'Group 3',
      searchPlaceholder: 'Search channels…',
      previewChannel1: 'Channel 1',
      previewChannel2: 'Channel 2',
      previewChannel3: 'Channel 3',
      previewPlay: 'opens channel',
      previewFav: 'Hold OK for favourite',
      benefit1Title: 'Pair from your phone',
      benefit1Text: 'Scan the QR code on the TV, paste the M3U address on your phone and continue with the remote.',
      benefit2Title: 'Play directly',
      benefit2Text: 'The TV connects directly to the provider. No browser CORS and no video stream through Cloud247.',
      benefit3Title: 'Find channels faster',
      benefit3Text: 'Favourites, search and clear groups make large playlists faster and easier to navigate. On tablets, fullscreen also supports touch and swipe channel switching.'
    }
  };

  function t(key) {
    return i18n[state.lang]?.[key] || i18n.no[key] || key;
  }

  function applyLanguage(lang) {
    state.lang = lang;
    localStorage.setItem('cloud247tv:lang', lang);
    document.documentElement.lang = lang === 'no' ? 'no' : 'en';

    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.dataset.i18n;
      if (key) el.textContent = t(key);
    });

    document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
      const key = el.dataset.i18nPlaceholder;
      if (key) el.placeholder = t(key);
    });

    document.querySelectorAll('[data-lang]').forEach((button) => {
      const active = button.dataset.lang === lang;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-pressed', String(active));
    });
  }

  document.querySelectorAll('[data-lang]').forEach((button) => {
    button.addEventListener('click', () => applyLanguage(button.dataset.lang));
  });

  applyLanguage(state.lang);
})();
