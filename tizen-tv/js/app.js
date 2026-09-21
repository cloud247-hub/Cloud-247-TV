(function () {
  'use strict';

  var VERSION = '1.0.0';
  var API = 'https://tv-api.cloud247.no';
  var MAX_M3U_BYTES = 64 * 1024 * 1024;
  var FAVORITES_KEY = 'cloud247tv:tizen:favorites';
  var SECURE_URL_KEY = 'playlist_url';
  var HOLD_MS = 650;
  var WINDOW_ROWS = 11;

  var state = {
    playlistName: 'Spilleliste',
    channels: [],
    byGroup: {},
    norwegian: [],
    premier: [],
    groups: [],
    favorites: loadFavorites(),
    activeGroup: '__all__',
    visibleChannels: [],
    groupIndex: 0,
    channelIndex: 0,
    focusArea: 'groups',
    pair: null,
    pairTimer: null,
    enterDownAt: 0,
    enterHeldChannelKey: '',
    fullscreen: false,
    overlayTimer: null,
    toastTimer: null
  };

  var el = {};
  var NORWAY_TOKEN = /(^|[\s|:_\-\[\]])NO($|[\s|:_\-\[\]])/;
  var NORWEGIAN_NAME = /^(NRK(?:\s|$)|TV\s?2(?:\s|$)|TVNORGE(?:\s|$)|FEM(?:\s|$)|MAX(?:\s|$)|VOX(?:\s|$)|EUROSPORT\s+NORGE(?:\s|$)|VISJON\s+NORGE(?:\s|$)|FRIKANALEN(?:\s|$)|MATKANALEN(?:\s|$)|HEIM(?:\s|$)|KANAL\s+10\s+NORGE(?:\s|$))/;

  function $(id) { return document.getElementById(id); }

  function bind() {
    el.setup = $('setupScreen');
    el.browse = $('browseScreen');
    el.player = $('playerScreen');
    el.qr = $('qrCode');
    el.pairCode = $('pairCode');
    el.pairStatus = $('pairStatus');
    el.newPair = $('newPairButton');
    el.playlistName = $('playlistName');
    el.playlistStats = $('playlistStats');
    el.groupList = $('groupList');
    el.channelList = $('channelList');
    el.channelHeading = $('channelHeading');
    el.channelCount = $('channelCount');
    el.changeList = $('changeListButton');
    el.playerOverlay = $('playerOverlay');
    el.playerClock = $('playerClock');
    el.playerChannel = $('playerChannel');
    el.playerError = $('playerError');
    el.toast = $('toast');
  }

  function safeJsonParse(value, fallback) {
    try { return JSON.parse(value); } catch (_) { return fallback; }
  }

  function loadFavorites() {
    var items = safeJsonParse(localStorage.getItem(FAVORITES_KEY) || '[]', []);
    var map = {};
    for (var i = 0; i < items.length; i++) map[items[i]] = true;
    return map;
  }

  function saveFavorites() {
    var items = [];
    for (var key in state.favorites) if (state.favorites.hasOwnProperty(key) && state.favorites[key]) items.push(key);
    localStorage.setItem(FAVORITES_KEY, JSON.stringify(items));
  }

  function favoriteKey(channel) {
    if (channel.tvgId) return 'id:' + channel.tvgId;
    return 'name:' + (channel.tvgName || channel.name) + '|group:' + channel.group;
  }

  function getStoredUrl() {
    try {
      if (window.tizen && tizen.keymanager) {
        return tizen.keymanager.getData({ name: SECURE_URL_KEY }, null) || '';
      }
    } catch (_) {}
    return localStorage.getItem('cloud247tv:tizen:playlist') || '';
  }

  function saveStoredUrl(url) {
    try {
      if (window.tizen && tizen.keymanager) {
        try { tizen.keymanager.removeData({ name: SECURE_URL_KEY }); } catch (_) {}
        tizen.keymanager.saveData(
          SECURE_URL_KEY,
          url,
          null,
          function () { localStorage.removeItem('cloud247tv:tizen:playlist'); },
          function () { localStorage.setItem('cloud247tv:tizen:playlist', url); }
        );
        return;
      }
    } catch (_) {}
    localStorage.setItem('cloud247tv:tizen:playlist', url);
  }

  function xhr(method, url, body, headers, onDone) {
    var request = new XMLHttpRequest();
    request.open(method, url, true);
    request.timeout = 20000;
    var key;
    if (headers) {
      for (key in headers) if (headers.hasOwnProperty(key)) request.setRequestHeader(key, headers[key]);
    }
    request.onload = function () {
      onDone(null, request);
    };
    request.onerror = function () { onDone(new Error('Nettverksfeil')); };
    request.ontimeout = function () { onDone(new Error('Tidsavbrudd')); };
    try { request.send(body == null ? null : body); } catch (error) { onDone(error); }
    return request;
  }

  function pairRequest(path, payload, callback) {
    xhr(
      'POST',
      API + path,
      JSON.stringify(payload || {}),
      {
        'Content-Type': 'application/json',
        'X-Cloud247-TV-Client': 'tizen-' + VERSION
      },
      function (error, request) {
        if (error) return callback(error);
        if (request.status === 204) return callback(null, null);
        var data = safeJsonParse(request.responseText || '{}', {});
        if (request.status < 200 || request.status >= 300) {
          return callback(new Error(data.error || ('HTTP ' + request.status)));
        }
        callback(null, data);
      }
    );
  }

  function startPairing() {
    stopPairing();
    showSetup();
    el.pairCode.textContent = '------';
    el.pairStatus.textContent = 'Lager sikker TV-kode …';
    el.qr.innerHTML = '<div class="qr-placeholder">QR</div>';

    pairRequest('/v1/pair/create', {}, function (error, data) {
      if (error) {
        el.pairStatus.textContent = 'Kunne ikke lage TV-kode: ' + cleanError(error);
        return;
      }
      state.pair = data;
      el.pairCode.textContent = data.code;
      el.pairStatus.textContent = 'Skann QR-koden med mobilen';
      renderQr(data.link);
      state.pairTimer = setTimeout(pollPairing, 1500);
    });
  }

  function pollPairing() {
    if (!state.pair) return;
    pairRequest('/v1/pair/poll', { code: state.pair.code, token: state.pair.token }, function (error, data) {
      if (error) {
        el.pairStatus.textContent = 'Venter på TV-kobling …';
        state.pairTimer = setTimeout(pollPairing, 4000);
        return;
      }
      if (data && data.url) {
        el.pairStatus.textContent = 'Spilleliste mottatt. Kobler til …';
        stopPairing();
        saveStoredUrl(data.url);
        loadPlaylist(data.url, false);
        return;
      }
      state.pairTimer = setTimeout(pollPairing, 2500);
    });
  }

  function stopPairing() {
    if (state.pairTimer) clearTimeout(state.pairTimer);
    state.pairTimer = null;
    state.pair = null;
  }

  function renderQr(url) {
    try {
      var qr = qrcode(0, 'M');
      qr.addData(url);
      qr.make();
      el.qr.innerHTML = qr.createSvgTag({ cellSize: 7, margin: 3, scalable: true });
    } catch (_) {
      el.qr.innerHTML = '<div class="qr-placeholder">QR</div>';
    }
  }

  function loadPlaylist(url, savedSource) {
    showSetup();
    el.pairStatus.textContent = savedSource ? 'Henter lagret spilleliste …' : 'Henter spilleliste …';

    var request = new XMLHttpRequest();
    request.open('GET', url, true);
    request.timeout = 30000;
    request.onprogress = function (event) {
      if (event.loaded > MAX_M3U_BYTES) {
        request.abort();
        el.pairStatus.textContent = 'Spillelisten er større enn 64 MiB.';
      }
    };
    request.onload = function () {
      if (request.status < 200 || request.status >= 300) {
        el.pairStatus.textContent = 'Kunne ikke hente spillelisten: HTTP ' + request.status;
        if (savedSource) startPairing();
        return;
      }
      if ((request.responseText || '').length > MAX_M3U_BYTES) {
        el.pairStatus.textContent = 'Spillelisten er større enn 64 MiB.';
        return;
      }
      try {
        var playlist = parseM3U(request.responseText || '');
        if (!playlist.channels.length) throw new Error('Fant ingen kanaler');
        applyPlaylist(playlist, hostName(url));
      } catch (error) {
        el.pairStatus.textContent = 'Kunne ikke lese spillelisten: ' + cleanError(error);
      }
    };
    request.onerror = function () {
      el.pairStatus.textContent = 'Kunne ikke hente spillelisten direkte fra leverandøren.';
      if (savedSource) startPairing();
    };
    request.ontimeout = function () {
      el.pairStatus.textContent = 'Spillelisten brukte for lang tid på å svare.';
    };
    try { request.send(); } catch (error) {
      el.pairStatus.textContent = 'Kunne ikke hente spillelisten: ' + cleanError(error);
    }
  }

  function hostName(url) {
    try {
      var a = document.createElement('a');
      a.href = url;
      return (a.hostname || 'Spilleliste').replace(/^www\./, '');
    } catch (_) { return 'Spilleliste'; }
  }

  function parseAttrs(value) {
    var out = {};
    var re = /([\w-]+)="([^"]*)"/g;
    var match;
    while ((match = re.exec(value))) out[match[1].toLowerCase()] = match[2];
    return out;
  }

  function parseM3U(text) {
    var channels = [];
    var pending = null;
    var start = 0;
    var len = text.length;

    function handleLine(raw) {
      var line = raw.replace(/^\s+|\s+$/g, '');
      if (!line) return;
      if (line.indexOf('#EXTINF:') === 0) {
        var comma = line.indexOf(',');
        var meta = comma >= 0 ? line.substring(0, comma) : line;
        var title = comma >= 0 ? line.substring(comma + 1).replace(/^\s+|\s+$/g, '') : '';
        var attrs = parseAttrs(meta);
        pending = {
          name: title || attrs['tvg-name'] || 'Uten navn',
          tvgName: attrs['tvg-name'] || '',
          tvgId: attrs['tvg-id'] || '',
          logo: attrs['tvg-logo'] || '',
          group: attrs['group-title'] || 'Andre',
          url: ''
        };
      } else if (line.indexOf('#EXTGRP:') === 0 && pending) {
        pending.group = line.substring(8).replace(/^\s+|\s+$/g, '') || 'Andre';
      } else if (line.charAt(0) !== '#' && pending) {
        pending.url = line;
        channels.push(pending);
        pending = null;
      }
    }

    for (var i = 0; i <= len; i++) {
      var code = i < len ? text.charCodeAt(i) : 10;
      if (code === 10) {
        var line = text.substring(start, i);
        if (line.charAt(line.length - 1) === '\r') line = line.substring(0, line.length - 1);
        if (start === 0 && line.charCodeAt(0) === 0xFEFF) line = line.substring(1);
        handleLine(line);
        start = i + 1;
      }
    }
    return { channels: channels };
  }

  function isPremier(channel) {
    return /^EPL/i.test((channel.name || '').replace(/^\s+/, ''));
  }

  function isNorwegian(channel) {
    var values = [channel.name, channel.tvgName, channel.group];
    for (var i = 0; i < values.length; i++) {
      var value = (values[i] || '').replace(/^\s+|\s+$/g, '').toUpperCase();
      if (!value) continue;
      if (value.indexOf('NORWAY') >= 0 || value.indexOf('NORWEGIAN') >= 0 || value.indexOf('NORGE') >= 0 || NORWAY_TOKEN.test(value)) {
        NORWAY_TOKEN.lastIndex = 0;
        return true;
      }
      NORWAY_TOKEN.lastIndex = 0;
    }
    return NORWEGIAN_NAME.test((channel.name || '').replace(/^\s+|\s+$/g, '').toUpperCase());
  }

  function buildIndex(channels) {
    var byGroup = {};
    var norwegian = [];
    var premier = [];
    for (var i = 0; i < channels.length; i++) {
      var channel = channels[i];
      if (!byGroup[channel.group]) byGroup[channel.group] = [];
      byGroup[channel.group].push(channel);
      if (isNorwegian(channel)) norwegian.push(channel);
      if (isPremier(channel)) premier.push(channel);
    }
    state.byGroup = byGroup;
    state.norwegian = norwegian;
    state.premier = premier;
  }

  function applyPlaylist(playlist, name) {
    state.channels = playlist.channels;
    state.playlistName = name || 'Spilleliste';
    buildIndex(state.channels);
    state.activeGroup = '__all__';
    state.groupIndex = 0;
    state.channelIndex = 0;
    state.focusArea = 'groups';
    buildGroups();
    updateVisibleChannels();
    el.playlistName.textContent = state.playlistName;
    el.playlistStats.textContent = state.channels.length + ' kanaler';
    showBrowse();
    render();
  }

  function buildGroups() {
    var favoriteCount = 0;
    var key;
    for (var i = 0; i < state.channels.length; i++) if (state.favorites[favoriteKey(state.channels[i])]) favoriteCount++;

    var groups = [
      { key: '__all__', label: 'Alle kanaler', count: state.channels.length },
      { key: '__favorites__', label: '★ Favoritter', count: favoriteCount }
    ];
    if (state.norwegian.length) groups.push({ key: '__norwegian__', label: 'Norske kanaler', count: state.norwegian.length });
    if (state.premier.length) groups.push({ key: '__premier__', label: 'Fotball', count: state.premier.length });

    var names = [];
    for (key in state.byGroup) if (state.byGroup.hasOwnProperty(key)) names.push(key);
    names.sort(function (a, b) { return a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : 0; });
    for (i = 0; i < names.length; i++) groups.push({ key: names[i], label: names[i], count: state.byGroup[names[i]].length });
    state.groups = groups;
  }

  function updateVisibleChannels() {
    var list;
    if (state.activeGroup === '__all__') list = state.channels;
    else if (state.activeGroup === '__norwegian__') list = state.norwegian;
    else if (state.activeGroup === '__premier__') list = state.premier;
    else if (state.activeGroup === '__favorites__') {
      list = [];
      for (var i = 0; i < state.channels.length; i++) if (state.favorites[favoriteKey(state.channels[i])]) list.push(state.channels[i]);
    } else list = state.byGroup[state.activeGroup] || [];

    state.visibleChannels = list;
    if (state.channelIndex >= list.length) state.channelIndex = Math.max(0, list.length - 1);
    el.channelCount.textContent = String(list.length);
    el.channelHeading.textContent = groupHeading(state.activeGroup);
  }

  function groupHeading(key) {
    if (key === '__all__') return 'ALLE KANALER';
    if (key === '__favorites__') return 'FAVORITTER';
    if (key === '__norwegian__') return 'NORSKE KANALER';
    if (key === '__premier__') return 'FOTBALL';
    return String(key || '').toUpperCase();
  }

  function render() {
    renderGroups();
    renderChannels();
  }

  function windowRange(index, count) {
    var half = Math.floor(WINDOW_ROWS / 2);
    var start = Math.max(0, index - half);
    var end = Math.min(count, start + WINDOW_ROWS);
    start = Math.max(0, end - WINDOW_ROWS);
    return { start: start, end: end };
  }

  function renderGroups() {
    el.groupList.innerHTML = '';
    var range = windowRange(state.groupIndex, state.groups.length);
    for (var i = range.start; i < range.end; i++) {
      var item = state.groups[i];
      var row = document.createElement('div');
      row.className = 'row' + (state.focusArea === 'groups' && i === state.groupIndex ? ' focused' : '');
      var label = document.createElement('span');
      label.textContent = item.label;
      var count = document.createElement('span');
      count.className = 'row-count';
      count.textContent = item.count;
      row.appendChild(label);
      row.appendChild(count);
      el.groupList.appendChild(row);
    }
  }

  function renderChannels() {
    el.channelList.innerHTML = '';
    var range = windowRange(state.channelIndex, state.visibleChannels.length);
    for (var i = range.start; i < range.end; i++) {
      var channel = state.visibleChannels[i];
      var row = document.createElement('div');
      row.className = 'row' + (state.focusArea === 'channels' && i === state.channelIndex ? ' focused' : '');

      var left = document.createElement('div');
      left.className = 'channel-left';

      var logo = document.createElement('div');
      logo.className = 'channel-logo';
      var initial = document.createElement('span');
      initial.textContent = initials(channel.name);
      logo.appendChild(initial);
      if (channel.logo) {
        var img = document.createElement('img');
        img.src = channel.logo;
        img.alt = '';
        img.onload = (function (container, fallback) {
          return function () { if (fallback.parentNode) container.removeChild(fallback); };
        }(logo, initial));
        img.onerror = function () { if (this.parentNode) this.parentNode.removeChild(this); };
        logo.appendChild(img);
      }

      var copy = document.createElement('div');
      copy.className = 'channel-copy';
      var name = document.createElement('strong');
      name.textContent = channel.name;
      var group = document.createElement('span');
      group.textContent = channel.group;
      copy.appendChild(name);
      copy.appendChild(group);
      left.appendChild(logo);
      left.appendChild(copy);

      var star = document.createElement('span');
      star.className = 'star';
      star.textContent = state.favorites[favoriteKey(channel)] ? '★' : '☆';

      row.appendChild(left);
      row.appendChild(star);
      el.channelList.appendChild(row);
    }
  }

  function initials(name) {
    var parts = String(name || '').replace(/^\s+|\s+$/g, '').split(/\s+/);
    var out = '';
    for (var i = 0; i < parts.length && i < 2; i++) if (parts[i]) out += parts[i].charAt(0).toUpperCase();
    return out || 'TV';
  }

  function activateGroup() {
    if (!state.groups.length) return;
    var item = state.groups[state.groupIndex];
    state.activeGroup = item.key;
    state.channelIndex = 0;
    updateVisibleChannels();
    if (state.visibleChannels.length) state.focusArea = 'channels';
    render();
  }

  function toggleFavorite(channel) {
    var key = favoriteKey(channel);
    if (state.favorites[key]) delete state.favorites[key]; else state.favorites[key] = true;
    saveFavorites();
    buildGroups();
    if (state.activeGroup === '__favorites__') updateVisibleChannels();
    render();
    toast(state.favorites[key] ? '★ Lagt til i Favoritter' : 'Fjernet fra Favoritter');
  }

  function playChannel(channel) {
    if (!channel || !channel.url) return;
    stopPairing();
    state.fullscreen = true;
    el.setup.classList.add('hidden');
    el.browse.classList.add('hidden');
    el.player.classList.remove('hidden');
    el.playerError.classList.add('hidden');
    el.playerChannel.textContent = channel.name;
    showOverlay();

    try {
      try { webapis.avplay.stop(); } catch (_) {}
      try { webapis.avplay.close(); } catch (_) {}
      webapis.avplay.open(channel.url);
      webapis.avplay.setDisplayRect(0, 0, 1920, 1080);
      webapis.avplay.setListener({
        onbufferingstart: function () {},
        onbufferingprogress: function () {},
        onbufferingcomplete: function () {},
        oncurrentplaytime: function () {},
        onevent: function () {},
        onstreamcompleted: function () {},
        onerror: function (eventType) { showPlayerError('Avspillingsfeil: ' + eventType); },
        onsubtitlechange: function () {},
        ondrmevent: function () {}
      });
      webapis.avplay.prepareAsync(
        function () {
          try {
            webapis.avplay.play();
            showOverlay();
          } catch (error) { showPlayerError(cleanError(error)); }
        },
        function (error) { showPlayerError('Kunne ikke starte kanalen: ' + cleanError(error)); }
      );
    } catch (error) {
      showPlayerError('Kunne ikke åpne kanalen: ' + cleanError(error));
    }
  }

  function stopPlayer() {
    try { webapis.avplay.stop(); } catch (_) {}
    try { webapis.avplay.close(); } catch (_) {}
    state.fullscreen = false;
    if (state.overlayTimer) clearTimeout(state.overlayTimer);
    el.player.classList.add('hidden');
    el.browse.classList.remove('hidden');
    el.playerError.classList.add('hidden');
    render();
  }

  function showOverlay() {
    if (!state.fullscreen) return;
    if (state.overlayTimer) clearTimeout(state.overlayTimer);
    el.playerClock.textContent = clockText();
    el.playerOverlay.classList.remove('fade');
    state.overlayTimer = setTimeout(function () {
      el.playerOverlay.classList.add('fade');
    }, 2500);
  }

  function showPlayerError(message) {
    el.playerError.textContent = message;
    el.playerError.classList.remove('hidden');
    showOverlay();
  }

  function clockText() {
    var d = new Date();
    var h = d.getHours();
    var m = d.getMinutes();
    return (h < 10 ? '0' : '') + h + ':' + (m < 10 ? '0' : '') + m;
  }

  function showSetup() {
    state.fullscreen = false;
    el.player.classList.add('hidden');
    el.browse.classList.add('hidden');
    el.setup.classList.remove('hidden');
  }

  function showBrowse() {
    el.setup.classList.add('hidden');
    el.player.classList.add('hidden');
    el.browse.classList.remove('hidden');
  }

  function toast(message) {
    if (state.toastTimer) clearTimeout(state.toastTimer);
    el.toast.textContent = message;
    el.toast.classList.remove('hidden');
    state.toastTimer = setTimeout(function () { el.toast.classList.add('hidden'); }, 1700);
  }

  function cleanError(error) {
    var message = error && (error.message || error.name) ? (error.message || error.name) : String(error || 'Ukjent feil');
    return message.replace(/https?:\/\/\S+/g, '[adresse]').substring(0, 120);
  }

  function keyDown(event) {
    var key = event.keyCode;

    if (state.fullscreen) {
      if (key === 10009) {
        event.preventDefault();
        stopPlayer();
        return;
      }
      if (key === 13 || key === 37 || key === 38 || key === 39 || key === 40) {
        event.preventDefault();
        showOverlay();
      }
      return;
    }

    if (!el.setup.classList.contains('hidden')) {
      if (key === 13) {
        event.preventDefault();
        startPairing();
      } else if (key === 10009) {
        try { tizen.application.getCurrentApplication().exit(); } catch (_) {}
      }
      return;
    }

    if (key === 10009) {
      event.preventDefault();
      if (state.focusArea === 'channels') {
        state.focusArea = 'groups';
        render();
      } else {
        startPairing();
      }
      return;
    }

    if (key === 37 && state.focusArea === 'channels') {
      event.preventDefault();
      state.focusArea = 'groups';
      render();
      return;
    }

    if (key === 39 && state.focusArea === 'groups' && state.visibleChannels.length) {
      event.preventDefault();
      state.focusArea = 'channels';
      render();
      return;
    }

    if (key === 38 || key === 40) {
      event.preventDefault();
      var delta = key === 38 ? -1 : 1;
      if (state.focusArea === 'groups') {
        state.groupIndex = Math.max(0, Math.min(state.groups.length - 1, state.groupIndex + delta));
      } else {
        state.channelIndex = Math.max(0, Math.min(state.visibleChannels.length - 1, state.channelIndex + delta));
      }
      render();
      return;
    }

    if (key === 13) {
      event.preventDefault();
      if (state.focusArea === 'groups') {
        if (!state.enterDownAt) state.enterDownAt = Date.now();
        return;
      }
      if (!state.enterDownAt) {
        state.enterDownAt = Date.now();
        if (state.visibleChannels[state.channelIndex]) state.enterHeldChannelKey = favoriteKey(state.visibleChannels[state.channelIndex]);
      }
    }
  }

  function keyUp(event) {
    if (event.keyCode !== 13 || state.fullscreen || !el.setup.classList.contains('hidden')) return;
    event.preventDefault();
    var duration = state.enterDownAt ? Date.now() - state.enterDownAt : 0;
    state.enterDownAt = 0;

    if (state.focusArea === 'groups') {
      activateGroup();
      return;
    }

    var channel = state.visibleChannels[state.channelIndex];
    if (!channel) return;
    if (duration >= HOLD_MS && favoriteKey(channel) === state.enterHeldChannelKey) toggleFavorite(channel);
    else playChannel(channel);
    state.enterHeldChannelKey = '';
  }

  function init() {
    bind();
    document.addEventListener('keydown', keyDown, false);
    document.addEventListener('keyup', keyUp, false);
    el.newPair.onclick = startPairing;
    el.changeList.onclick = startPairing;

    var saved = getStoredUrl();
    if (saved) loadPlaylist(saved, true);
    else startPairing();

    console.log('Cloud247 TV Tizen v' + VERSION);
  }

  document.addEventListener('DOMContentLoaded', init);
}());
