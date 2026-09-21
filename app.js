(() => {
  'use strict';

  const VERSION = '1.0.5';
  const PROXY_URL = 'https://tv-api.cloud247.no/v1/fetch';
  const state = {
    channels: [], groups: new Map(), selectedGroup: '__all__', selectedChannel: null,
    favorites: new Set(loadJson('cloud247tv:favorites', [])), epg: new Map(), hls: null,
    playlistName: 'Min spilleliste', lang: localStorage.getItem('cloud247tv:lang') || 'no', epgHint: ''
  };

  const $ = (id) => document.getElementById(id);
  const els = {
    welcome: $('welcome'), tvApp: $('tvApp'), playlistUrl: $('playlistUrl'), playlistFile: $('playlistFile'),
    loadUrl: $('loadUrl'), sourceError: $('sourceError'), playlistName: $('playlistName'), playlistStats: $('playlistStats'),
    groupCount: $('groupCount'), groups: $('groups'), selectedGroupLabel: $('selectedGroupLabel'), visibleCount: $('visibleCount'),
    channelSearch: $('channelSearch'), channelList: $('channelList'), emptyChannels: $('emptyChannels'), video: $('video'),
    videoPlaceholder: $('videoPlaceholder'), playerMessage: $('playerMessage'), currentLogo: $('currentLogo'), currentName: $('currentName'),
    currentGroup: $('currentGroup'), favoriteCurrent: $('favoriteCurrent'), nowTitle: $('nowTitle'), nowTime: $('nowTime'), nextTitle: $('nextTitle'), nextTime: $('nextTime'),
    progressWrap: $('progressWrap'), progressBar: $('progressBar'), replaceButton: $('replaceButton'), epgButton: $('epgButton'),
    epgDialog: $('epgDialog'), epgUrl: $('epgUrl'), epgFile: $('epgFile'), loadEpgUrl: $('loadEpgUrl'), epgError: $('epgError'), epgHint: $('epgHint'), toast: $('toast')
  };

  const i18n = {
    no: {
      navAndroid:'Android TV',navSamsung:'Samsung TV',navPair:'Koble TV',navWeb:'Webspiller',nativeApp:'ANDROID TV · v1.1.6 · SAMSUNG TV · v1.0.2',
      eyebrowNew:'DIN TV · DIN SPILLELISTE',heroNew1:'TV-en din.',heroNew2:'Spillelisten din.',
      leadNew:'Cloud247 TV gir deg et ryddig TV-grensesnitt for din egen M3U-liste på Android TV og Samsung TV – med QR-pairing, favoritter, grupper og direkte avspilling fra TV-en.',
      pairTv:'Koble TV',downloadAndroid:'Android TV',downloadSamsung:'Samsung TV',tryWeb:'Prøv webspilleren',
      pointDirect:'Direkte avspilling på TV-en',pointQr:'QR-pairing på få sekunder',pointLocal:'Ingen videoproxy',
      previewSub:'DIN TV · DIN SPILLELISTE',previewGroupAll:'Alle kanaler',previewGroup1:'Gruppe 1',previewGroup2:'Gruppe 2',previewGroup3:'Gruppe 3',previewChannel1:'Kanal 1',previewChannel2:'Kanal 2',previewChannel3:'Kanal 3',previewPlay:'åpner kanal',previewFav:'Hold OK for favoritt',
      benefit1Title:'Koble på mobilen',benefit1Text:'Skann QR-koden på TV-en, lim inn M3U-adressen på mobilen og fortsett med fjernkontrollen.',
      benefit2Title:'Spill direkte',benefit2Text:'TV-en kobler seg direkte til leverandøren. Ingen nettleser-CORS og ingen videostrøm gjennom Cloud247.',
      benefit3Title:'Finn kanalene raskt',benefit3Text:'Favoritter, søk og oversiktlige grupper gjør store spillelister raske og enkle å navigere.',
      webEyebrow:'WEBSPILLER · BETA',webTitle:'Vil du teste i nettleseren?',webLead:'Du kan fortsatt åpne M3U-listen her. Selve videostrømmen må støtte nettleseravspilling, CORS og riktig kodek. For vanlig TV-bruk anbefales Android TV- eller Samsung TV-appen.',
      browserNoteTitle:'Hvorfor kan VLC virke når nettleseren ikke gjør det?',browserNoteText:'Nettlesere håndhever CORS og mixed-content-regler som native spillere ikke har. Derfor kan enkelte IPTV-kilder fungere i TV-appene eller VLC selv om nettleseren blokkerer dem.',
      eyebrow:'DIN SPILLELISTE · DIN TV',hero1:'TV uten',hero2:'unødvendig støy',lead:'Legg inn din egen M3U-liste og få et ryddig kanalgrensesnitt med favoritter, grupper og programoversikt.',tagLocal:'Spillelisten lagres ikke',tagEpg:'XMLTV / EPG',tagHls:'HLS-avspilling',start:'KOM I GANG',sourceTitle:'Legg til TV-kilde',urlTab:'M3U-adresse',fileTab:'M3U-fil',m3uAddress:'M3U / M3U8-adresse',openPlaylist:'Åpne spilleliste',chooseFile:'Velg M3U-fil',chooseFileHelp:'eller slipp filen her',privacy:'URL-import går via Cloud247-proxyen, men spillelisten og innloggingsdetaljene lagres ikke. Lokale filer blir i nettleseren.',epgButton:'EPG',replace:'Bytt liste',groups:'GRUPPER',allChannels:'ALLE KANALER',channels:'Kanaler',search:'Søk kanaler',searchPlaceholder:'Søk kanaler…',noChannels:'Ingen kanaler funnet',trySearch:'Prøv et annet søk eller en annen gruppe.',pickChannel:'Velg en kanal',pickChannelHelp:'Avspillingen starter her.',nowWatching:'SER PÅ',nothingSelected:'Ingen kanal valgt',now:'NÅ',next:'NESTE',noEpg:'Ingen EPG lastet',playerHelp:'Noen IPTV-kilder blokkerer nettlesere med CORS eller bruker kodeker nettleseren ikke støtter.',epgTitle:'Programguide',epgIntro:'Legg til XMLTV fra en URL eller fil. Guiden brukes kun i denne fanen.',epgUrl:'XMLTV-adresse',loadEpg:'Last EPG',chooseXml:'Velg XMLTV-fil',favorites:'Favoritter',all:'Alle kanaler',other:'Andre',channelsWord:'kanaler',loading:'Laster…',cors:'Kunne ikke hente via Cloud247-proxyen. Kontroller adressen, eller at IPTV-leverandøren tillater Cloudflare å hente kilden.',badPlaylist:'Fant ingen kanaler i spillelisten.',fileError:'Kunne ikke lese filen.',epgLoaded:'EPG lastet',epgFail:'Kunne ikke lese XMLTV-guiden.',streamFail:'Kanalen kunne ikke spilles i nettleseren. Det kan skyldes CORS, HTTP/HTTPS eller et kodekformat som ikke støttes.',hlsMissing:'HLS-avspiller kunne ikke lastes. Kontroller internettforbindelsen eller Content Security Policy.',favoriteAdded:'Lagt til i favoritter',favoriteRemoved:'Fjernet fra favoritter',listLoaded:'Spilleliste lastet'},
    en: {
      navAndroid:'Android TV',navSamsung:'Samsung TV',navPair:'Pair TV',navWeb:'Web player',nativeApp:'ANDROID TV · v1.1.6 · SAMSUNG TV · v1.0.2',
      eyebrowNew:'YOUR TV · YOUR PLAYLIST',heroNew1:'Your TV.',heroNew2:'Your playlist.',
      leadNew:'Cloud247 TV gives you a clean TV interface for your own M3U playlist on Android TV and Samsung TV, with QR pairing, favourites, groups and direct playback on the TV.',
      pairTv:'Pair TV',downloadAndroid:'Android TV',downloadSamsung:'Samsung TV',tryWeb:'Try the web player',
      pointDirect:'Direct playback on the TV',pointQr:'QR pairing in seconds',pointLocal:'No video proxy',
      previewSub:'YOUR TV · YOUR PLAYLIST',previewGroupAll:'All channels',previewGroup1:'Group 1',previewGroup2:'Group 2',previewGroup3:'Group 3',previewChannel1:'Channel 1',previewChannel2:'Channel 2',previewChannel3:'Channel 3',previewPlay:'opens channel',previewFav:'Hold OK for favourite',
      benefit1Title:'Pair from your phone',benefit1Text:'Scan the QR code on the TV, paste the M3U address on your phone and continue with the remote.',
      benefit2Title:'Play directly',benefit2Text:'The TV connects directly to the provider. No browser CORS and no video stream through Cloud247.',
      benefit3Title:'Find channels faster',benefit3Text:'Favourites, search and clear groups make large playlists faster and easier to navigate.',
      webEyebrow:'WEB PLAYER · BETA',webTitle:'Want to test in the browser?',webLead:'You can still open the M3U playlist here. The actual video stream must support browser playback, CORS and a compatible codec. For normal TV use, the Android TV or Samsung TV app is recommended.',
      browserNoteTitle:'Why can VLC work when the browser does not?',browserNoteText:'Browsers enforce CORS and mixed-content rules that native players do not. Some IPTV sources can therefore work in the TV apps or VLC even when the browser blocks them.',
      eyebrow:'YOUR PLAYLIST · YOUR TV',hero1:'TV without',hero2:'unnecessary noise',lead:'Add your own M3U playlist and get a clean channel interface with favourites, groups and programme information.',tagLocal:'Playlist is not stored',tagEpg:'XMLTV / EPG',tagHls:'HLS playback',start:'GET STARTED',sourceTitle:'Add TV source',urlTab:'M3U address',fileTab:'M3U file',m3uAddress:'M3U / M3U8 address',openPlaylist:'Open playlist',chooseFile:'Choose M3U file',chooseFileHelp:'or drop the file here',privacy:'URL imports pass through the Cloud247 proxy, but playlists and login details are not stored. Local files stay in your browser.',epgButton:'EPG',replace:'Replace list',groups:'GROUPS',allChannels:'ALL CHANNELS',channels:'Channels',search:'Search channels',searchPlaceholder:'Search channels…',noChannels:'No channels found',trySearch:'Try another search or group.',pickChannel:'Choose a channel',pickChannelHelp:'Playback starts here.',nowWatching:'WATCHING',nothingSelected:'No channel selected',now:'NOW',next:'NEXT',noEpg:'No EPG loaded',playerHelp:'Some IPTV sources block browsers with CORS or use codecs the browser does not support.',epgTitle:'Programme guide',epgIntro:'Add XMLTV from a URL or file. The guide is only kept in this tab.',epgUrl:'XMLTV address',loadEpg:'Load EPG',chooseXml:'Choose XMLTV file',favorites:'Favourites',all:'All channels',other:'Other',channelsWord:'channels',loading:'Loading…',cors:'Could not fetch through the Cloud247 proxy. Check the address or whether the IPTV provider allows Cloudflare to fetch the source.',badPlaylist:'No channels were found in the playlist.',fileError:'Could not read the file.',epgLoaded:'EPG loaded',epgFail:'Could not read the XMLTV guide.',streamFail:'This channel could not be played in the browser. This may be caused by CORS, HTTP/HTTPS or an unsupported codec.',hlsMissing:'The HLS player could not be loaded. Check your internet connection or Content Security Policy.',favoriteAdded:'Added to favourites',favoriteRemoved:'Removed from favourites',listLoaded:'Playlist loaded'}
  };
  const t = (key) => i18n[state.lang]?.[key] || i18n.no[key] || key;

  function loadJson(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
  function saveFavorites(){ localStorage.setItem('cloud247tv:favorites', JSON.stringify([...state.favorites])); }
  function toast(msg){ els.toast.textContent = msg; els.toast.classList.add('show'); clearTimeout(toast.timer); toast.timer=setTimeout(()=>els.toast.classList.remove('show'),1800); }
  function showError(el,msg){ el.textContent=msg; el.hidden=false; }
  function hideError(el){ el.hidden=true; el.textContent=''; }
  function channelKey(ch){ return ch.tvgId ? `id:${ch.tvgId}` : `name:${ch.tvgName || ch.name}|group:${ch.group}`; }
  function normalizeGroup(g){ return (g || '').trim() || t('other'); }

  function parseAttrs(input){
    const attrs={}; const re=/([\w-]+)="([^"]*)"/g; let m;
    while((m=re.exec(input))) attrs[m[1].toLowerCase()]=m[2];
    return attrs;
  }

  function parseM3U(text){
    const lines=text.replace(/^\uFEFF/,'').split(/\r?\n/); const channels=[]; let pending=null; let headerEpg='';
    if(lines[0] && lines[0].startsWith('#EXTM3U')){
      const a=parseAttrs(lines[0]); headerEpg=a['url-tvg'] || a['x-tvg-url'] || '';
    }
    for(const raw of lines){
      const line=raw.trim(); if(!line) continue;
      if(line.startsWith('#EXTINF:')){
        const comma=line.indexOf(','); const meta=comma>=0?line.slice(0,comma):line; const title=comma>=0?line.slice(comma+1).trim():''; const a=parseAttrs(meta);
        pending={name:title || a['tvg-name'] || 'Uten navn', tvgName:a['tvg-name']||'', tvgId:a['tvg-id']||'', logo:a['tvg-logo']||'', group:normalizeGroup(a['group-title']), url:''};
      } else if(line.startsWith('#EXTGRP:') && pending){
        pending.group=normalizeGroup(line.slice(8));
      } else if(!line.startsWith('#') && pending){
        pending.url=line; pending.index=channels.length; channels.push(pending); pending=null;
      }
    }
    return {channels, headerEpg};
  }

  async function fetchText(url,kind='playlist'){
    const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),25000);
    try{
      let r;
      try {
        r=await fetch(PROXY_URL,{
          method:'POST',
          signal:controller.signal,
          credentials:'omit',
          cache:'no-store',
          headers:{'Content-Type':'application/json'},
          body:JSON.stringify({url,kind})
        });
      } catch (cause) {
        const error=new Error('proxy_unreachable');
        error.code='proxy_unreachable';
        error.cause=cause;
        throw error;
      }
      if(!r.ok){
        let payload={};
        try{payload=await r.json();}catch{}
        const code=payload?.error||'';
        const error=new Error(code||`HTTP ${r.status}`);
        error.code=code;
        error.status=r.status;
        error.detail=typeof payload?.detail==='string' ? payload.detail : '';
        error.upstreamStatus=payload?.upstream_status;
        throw error;
      }
      return await r.text();
    } finally {clearTimeout(timer);}
  }

  function proxyErrorMessage(error){
    const code=error?.code || error?.message || '';
    if(code==='proxy_unreachable') return state.lang==='no'
      ? 'Får ikke kontakt med tv-api.cloud247.no. Åpne https://tv-api.cloud247.no/health og kontroller at Workeren er deployet på Custom Domain.'
      : 'Cannot reach tv-api.cloud247.no. Open https://tv-api.cloud247.no/health and verify that the Worker is deployed on the Custom Domain.';
    if(code==='origin_not_allowed') return state.lang==='no'
      ? 'Proxyen avviste Origin. Åpne appen fra https://tv.cloud247.no – ikke GitHub Pages-adressen.'
      : 'The proxy rejected the Origin. Open the app from https://tv.cloud247.no, not the GitHub Pages URL.';
    if(code==='invalid_url' || code==='scheme_not_allowed') return state.lang==='no'
      ? 'IPTV-adressen er ugyldig. Kun http:// og https:// støttes.'
      : 'The IPTV address is invalid. Only http:// and https:// are supported.';
    if(code==='blocked_host' || code==='redirect_blocked') return state.lang==='no'
      ? 'Proxyen blokkerte adressen av sikkerhetsgrunner fordi den peker mot et lokalt eller privat mål.'
      : 'The proxy blocked the address for security reasons because it points to a local or private target.';
    if(code==='port_not_allowed') return state.lang==='no'
      ? 'IPTV-adressen bruker en port som proxyen ikke tillater ennå. Port 80, 443, 8080 og 8443 er tillatt i denne versjonen.'
      : 'The IPTV address uses a port the proxy does not allow yet. Ports 80, 443, 8080 and 8443 are allowed in this version.';
    if(code==='upstream_http_401') return state.lang==='no'
      ? 'IPTV-leverandøren svarte 401. Kontroller brukernavn, passord eller token i M3U-adressen.'
      : 'The IPTV provider returned 401. Check the username, password or token in the M3U address.';
    if(code==='upstream_http_403') return state.lang==='no'
      ? 'IPTV-leverandøren svarte 403. Leverandøren kan blokkere Cloudflare/IP-adressen eller kreve en annen klient.'
      : 'The IPTV provider returned 403. The provider may block Cloudflare/IP addresses or require a different client.';
    if(code==='upstream_http_404') return state.lang==='no'
      ? 'IPTV-leverandøren svarte 404. M3U-adressen finnes ikke eller er utløpt.'
      : 'The IPTV provider returned 404. The M3U address does not exist or has expired.';
    if(code==='upstream_timeout') return state.lang==='no'
      ? 'IPTV-leverandøren svarte ikke innen 15 sekunder.'
      : 'The IPTV provider did not respond within 15 seconds.';
    if(code==='upstream_fetch_failed') return state.lang==='no'
      ? 'Cloudflare klarte ikke å koble til IPTV-leverandøren.'
      : 'Cloudflare could not connect to the IPTV provider.';
    if(code==='upstream_too_large') return state.lang==='no'
      ? 'Spillelisten er større enn proxygrensen på 8 MiB.'
      : 'The playlist is larger than the 8 MiB proxy limit.';
    if(code==='unsupported_upstream_type') return state.lang==='no'
      ? 'Leverandøren returnerte en innholdstype proxyen ikke godtar som M3U/XMLTV.'
      : 'The provider returned a content type the proxy does not accept as M3U/XMLTV.';
    if(/^upstream_http_\d+$/.test(code)){
      const status=code.replace('upstream_http_','');
      const base=state.lang==='no'
        ? `IPTV-leverandøren svarte med egendefinert feil ${status}.`
        : `The IPTV provider returned custom error ${status}.`;
      return error?.detail ? `${base} Detalj: ${error.detail}` : base;
    }
    return t('cors');
  }

  function applyPlaylist(parsed,name){
    if(!parsed.channels.length) throw new Error(t('badPlaylist'));
    state.channels=parsed.channels; state.playlistName=name || 'Min spilleliste'; state.groups=new Map(); state.selectedGroup='__all__'; state.selectedChannel=null; state.epgHint=parsed.headerEpg||'';
    for(const ch of state.channels){ if(!state.groups.has(ch.group)) state.groups.set(ch.group,0); state.groups.set(ch.group,state.groups.get(ch.group)+1); }
    els.playlistName.textContent=state.playlistName; els.playlistStats.textContent=`${state.channels.length} ${t('channelsWord')}`; els.groupCount.textContent=state.groups.size;
    els.epgHint.textContent=state.epgHint ? (state.lang==='no'?'Spillelisten inneholder en foreslått EPG-kilde.':'The playlist contains a suggested EPG source.') : '';
    els.epgUrl.value=state.epgHint;
    renderGroups(); renderChannels(); resetPlayer();
    els.welcome.hidden=true; els.tvApp.hidden=false; window.scrollTo({top:0,behavior:'smooth'}); toast(t('listLoaded'));
  }

  function redactUrl(url){ try {const u=new URL(url); if(u.username)u.username='***'; if(u.password)u.password='***'; ['username','password','token','key'].forEach(k=>{if(u.searchParams.has(k))u.searchParams.set(k,'***')}); return u.toString();}catch{return 'XMLTV URL';}}

  function renderGroups(){
    els.groups.replaceChildren(); const items=[['__all__',t('all'),state.channels.length],['__fav__',t('favorites'),state.channels.filter(c=>state.favorites.has(channelKey(c))).length],...[...state.groups.entries()].sort((a,b)=>a[0].localeCompare(b[0],state.lang==='no'?'nb':'en')).map(([g,c])=>[g,g,c])];
    for(const [key,label,count] of items){
      const b=document.createElement('button'); b.type='button'; b.className='group-button'+(key===state.selectedGroup?' is-active':'')+(key==='__fav__'?' favorite-group':''); b.dataset.group=key;
      const l=document.createElement('span'); l.textContent=key==='__fav__'?`★ ${label}`:label; const n=document.createElement('span'); n.textContent=count; b.append(l,n); b.addEventListener('click',()=>{state.selectedGroup=key; els.channelSearch.value=''; renderGroups(); renderChannels();}); els.groups.append(b);
    }
  }

  function currentFiltered(){
    const q=els.channelSearch.value.trim().toLocaleLowerCase(); return state.channels.filter(ch=>{
      const groupOk=state.selectedGroup==='__all__' || (state.selectedGroup==='__fav__'?state.favorites.has(channelKey(ch)):ch.group===state.selectedGroup);
      const searchOk=!q || `${ch.name} ${ch.tvgName} ${ch.group}`.toLocaleLowerCase().includes(q); return groupOk&&searchOk;
    });
  }

  function renderChannels(){
    const list=currentFiltered(); els.channelList.replaceChildren(); els.visibleCount.textContent=list.length; els.emptyChannels.hidden=!!list.length;
    const label=state.selectedGroup==='__all__'?t('allChannels'):state.selectedGroup==='__fav__'?t('favorites').toUpperCase():state.selectedGroup.toUpperCase(); els.selectedGroupLabel.textContent=label;
    const frag=document.createDocumentFragment();
    for(const ch of list){
      const row=document.createElement('button'); row.type='button'; row.className='channel-row'+(state.selectedChannel===ch?' is-active':''); row.dataset.index=ch.index;
      const logo=document.createElement('span'); logo.className='channel-logo'; if(ch.logo){const img=document.createElement('img');img.src=ch.logo;img.alt='';img.loading='lazy';img.referrerPolicy='no-referrer';img.addEventListener('error',()=>{logo.replaceChildren(document.createTextNode(initials(ch.name)));});logo.append(img);}else logo.textContent=initials(ch.name);
      const text=document.createElement('span'); text.className='channel-text'; const strong=document.createElement('strong'); strong.textContent=ch.name; const sub=document.createElement('span'); sub.textContent=programmeLine(ch); text.append(strong,sub);
      const fav=document.createElement('span'); fav.className='mini-favorite'+(state.favorites.has(channelKey(ch))?' is-favorite':''); fav.textContent=state.favorites.has(channelKey(ch))?'★':'☆'; fav.title=t('favorites');
      row.append(logo,text,fav); row.addEventListener('click',(ev)=>{const rect=fav.getBoundingClientRect(); if(ev.clientX && ev.clientX>=rect.left-5){toggleFavorite(ch);return;} selectChannel(ch);}); frag.append(row);
    }
    els.channelList.append(frag);
  }

  function initials(name){return name.split(/\s+/).filter(Boolean).slice(0,2).map(s=>s[0]).join('').toUpperCase()||'TV';}
  function programmeLine(ch){const p=getProgramme(ch); return p.now?.title || ch.group;}

  function selectChannel(ch){
    state.selectedChannel=ch; renderChannels(); els.currentName.textContent=ch.name; els.currentGroup.textContent=ch.group; els.favoriteCurrent.disabled=false; updateFavoriteCurrent();
    els.currentLogo.replaceChildren(); if(ch.logo){const img=document.createElement('img');img.src=ch.logo;img.alt='';img.referrerPolicy='no-referrer';img.addEventListener('error',()=>{els.currentLogo.replaceChildren(Object.assign(document.createElement('span'),{textContent:initials(ch.name)}));});els.currentLogo.append(img);}else{const s=document.createElement('span');s.textContent=initials(ch.name);els.currentLogo.append(s);} updateProgramme(); playStream(ch.url);
  }

  function resetPlayer(){
    destroyHls(); els.video.removeAttribute('src'); els.video.load(); els.videoPlaceholder.hidden=false; els.playerMessage.hidden=true; els.currentName.textContent=t('nothingSelected'); els.currentGroup.textContent='—'; els.currentLogo.innerHTML='<span>TV</span>'; els.favoriteCurrent.disabled=true; els.nowTitle.textContent=t('noEpg'); els.nowTime.textContent='—'; els.nextTitle.textContent='—'; els.nextTime.textContent='—'; els.progressWrap.hidden=true;
  }

  function destroyHls(){ if(state.hls){state.hls.destroy();state.hls=null;} }
  function isHlsUrl(url){ return /\.m3u8(?:$|\?)/i.test(url) || /(?:type|output)=m3u8/i.test(url); }
  function showPlayerMessage(msg){els.playerMessage.textContent=msg;els.playerMessage.hidden=false;}

  function playStream(url){
    destroyHls(); els.playerMessage.hidden=true; els.videoPlaceholder.hidden=true; els.video.pause(); els.video.removeAttribute('src'); els.video.load();
    const hlsLikely=isHlsUrl(url);
    if(hlsLikely && window.Hls && Hls.isSupported()){
      const hls=new Hls({enableWorker:true,lowLatencyMode:true,backBufferLength:60,maxBufferLength:30}); state.hls=hls; hls.loadSource(url); hls.attachMedia(els.video);
      hls.on(Hls.Events.MANIFEST_PARSED,()=>els.video.play().catch(()=>{}));
      hls.on(Hls.Events.ERROR,(_,data)=>{if(!data.fatal)return; if(data.type===Hls.ErrorTypes.NETWORK_ERROR){showPlayerMessage(t('streamFail'));hls.startLoad();} else if(data.type===Hls.ErrorTypes.MEDIA_ERROR){hls.recoverMediaError();} else {showPlayerMessage(t('streamFail'));destroyHls();}});
    } else if(hlsLikely && !window.Hls && !els.video.canPlayType('application/vnd.apple.mpegurl')) { showPlayerMessage(t('hlsMissing')); }
    else { els.video.src=url; els.video.play().catch(()=>{}); }
  }

  function toggleFavorite(ch){
    const key=channelKey(ch); const add=!state.favorites.has(key); if(add)state.favorites.add(key);else state.favorites.delete(key); saveFavorites(); renderGroups(); renderChannels(); if(state.selectedChannel===ch) updateFavoriteCurrent(); toast(add?t('favoriteAdded'):t('favoriteRemoved'));
  }
  function updateFavoriteCurrent(){ const active=state.selectedChannel && state.favorites.has(channelKey(state.selectedChannel)); els.favoriteCurrent.setAttribute('aria-pressed',String(!!active)); els.favoriteCurrent.textContent=active?'★':'☆'; }

  function parseXmltvDate(value){
    if(!value)return null; const m=value.trim().match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4}|Z)?/); if(!m)return null;
    const [,Y,M,D,h,min,s='00',tz='Z']=m; let iso=`${Y}-${M}-${D}T${h}:${min}:${s}`; if(tz==='Z'||!tz)iso+='Z'; else iso+=`${tz.slice(0,3)}:${tz.slice(3)}`; const d=new Date(iso); return Number.isNaN(d.getTime())?null:d;
  }

  function parseXmltv(text){
    const doc=new DOMParser().parseFromString(text,'application/xml'); if(doc.querySelector('parsererror'))throw new Error('Invalid XML');
    const map=new Map(); const aliases=new Map();
    for(const channel of doc.querySelectorAll('channel')){
      const id=channel.getAttribute('id'); if(!id)continue;
      for(const name of channel.querySelectorAll('display-name')){ const value=(name.textContent||'').trim(); if(value)aliases.set(value,id); }
    }
    for(const p of doc.querySelectorAll('programme')){
      const id=p.getAttribute('channel');const start=parseXmltvDate(p.getAttribute('start'));const stop=parseXmltvDate(p.getAttribute('stop'));if(!id||!start)continue;
      const title=(p.querySelector('title')?.textContent||'').trim()||'Uten tittel';const item={start,stop,title};if(!map.has(id))map.set(id,[]);map.get(id).push(item);
    }
    for(const arr of map.values())arr.sort((a,b)=>a.start-b.start);
    for(const [name,id] of aliases){ if(map.has(id) && !map.has(name))map.set(name,map.get(id)); }
    return map;
  }

  function epgCandidates(ch){ return [ch.tvgId,ch.tvgName,ch.name].filter(Boolean); }
  function getProgramme(ch){
    const now=new Date(); let arr=null; for(const id of epgCandidates(ch)){ if(state.epg.has(id)){arr=state.epg.get(id);break;} } if(!arr)return {now:null,next:null};
    let current=null,next=null; for(let i=0;i<arr.length;i++){const p=arr[i];const stop=p.stop||arr[i+1]?.start;if(p.start<=now && (!stop||now<stop)){current=p;next=arr[i+1]||null;break;}if(p.start>now){next=p;break;}}
    return {now:current,next};
  }

  function formatTime(d){return d?new Intl.DateTimeFormat(state.lang==='no'?'nb-NO':'en-GB',{hour:'2-digit',minute:'2-digit'}).format(d):'—';}
  function updateProgramme(){
    const ch=state.selectedChannel;if(!ch)return;const p=getProgramme(ch);els.nowTitle.textContent=p.now?.title||t('noEpg');els.nowTime.textContent=p.now?`${formatTime(p.now.start)} – ${formatTime(p.now.stop)}`:'—';els.nextTitle.textContent=p.next?.title||'—';els.nextTime.textContent=p.next?formatTime(p.next.start):'—';
    if(p.now?.stop){const now=Date.now(),total=p.now.stop-p.now.start,done=now-p.now.start;els.progressBar.style.width=`${Math.max(0,Math.min(100,done/total*100))}%`;els.progressWrap.hidden=false;}else els.progressWrap.hidden=true;
  }

  async function loadPlaylistUrl(){
    hideError(els.sourceError); const url=els.playlistUrl.value.trim(); if(!url)return; els.loadUrl.disabled=true; const old=els.loadUrl.firstElementChild?.textContent; if(els.loadUrl.firstElementChild)els.loadUrl.firstElementChild.textContent=t('loading');
    try{const text=await fetchText(url,'playlist');const parsed=parseM3U(text);applyPlaylist(parsed,hostName(url));els.playlistUrl.value='';}
    catch(e){showError(els.sourceError,e.message===t('badPlaylist')?e.message:proxyErrorMessage(e));}
    finally{els.loadUrl.disabled=false;if(els.loadUrl.firstElementChild)els.loadUrl.firstElementChild.textContent=old||t('openPlaylist');}
  }
  function hostName(url){try{return new URL(url).hostname.replace(/^www\./,'');}catch{return 'Min spilleliste';}}

  async function loadEpgFromText(text){
    try{state.epg=parseXmltv(text);hideError(els.epgError);els.epgUrl.value='';els.epgHint.textContent=`${state.epg.size} ${state.lang==='no'?'EPG-kanaler':'EPG channels'}`;renderChannels();updateProgramme();toast(t('epgLoaded'));els.epgDialog.close();}
    catch{showError(els.epgError,t('epgFail'));}
  }

  function applyLanguage(lang){
    state.lang=lang;localStorage.setItem('cloud247tv:lang',lang);document.documentElement.lang=lang==='no'?'no':'en';
    document.querySelectorAll('[data-i18n]').forEach(el=>{const k=el.dataset.i18n;if(t(k))el.textContent=t(k)});document.querySelectorAll('[data-i18n-placeholder]').forEach(el=>el.placeholder=t(el.dataset.i18nPlaceholder));
    document.querySelectorAll('[data-lang]').forEach(b=>{const active=b.dataset.lang===lang;b.classList.toggle('is-active',active);b.setAttribute('aria-pressed',String(active));});
    if(state.channels.length){for(const ch of state.channels){if(!ch.group)ch.group=t('other')}renderGroups();renderChannels();els.playlistStats.textContent=`${state.channels.length} ${t('channelsWord')}`;updateProgramme();}
  }

  async function loadPlaylistFile(file){
    if(!file)return; hideError(els.sourceError);
    try{applyPlaylist(parseM3U(await file.text()),file.name.replace(/\.m3u8?$/i,''));}
    catch(e){showError(els.sourceError,e.message||t('fileError'));}
  }

  document.querySelectorAll('[data-source-tab]').forEach(btn=>btn.addEventListener('click',()=>{document.querySelectorAll('[data-source-tab]').forEach(b=>{const active=b===btn;b.classList.toggle('is-active',active);b.setAttribute('aria-selected',String(active));});$('urlPane').hidden=btn.dataset.sourceTab!=='url';$('filePane').hidden=btn.dataset.sourceTab!=='file';}));
  document.querySelectorAll('[data-lang]').forEach(b=>b.addEventListener('click',()=>applyLanguage(b.dataset.lang)));
  els.loadUrl.addEventListener('click',loadPlaylistUrl); els.playlistUrl.addEventListener('keydown',e=>{if(e.key==='Enter')loadPlaylistUrl()});
  els.playlistFile.addEventListener('change',async()=>{await loadPlaylistFile(els.playlistFile.files?.[0]);els.playlistFile.value='';});
  const fileDrop=document.querySelector('.file-drop');
  fileDrop.addEventListener('dragover',e=>{e.preventDefault();fileDrop.classList.add('is-dragging');});
  fileDrop.addEventListener('dragleave',()=>fileDrop.classList.remove('is-dragging'));
  fileDrop.addEventListener('drop',async e=>{e.preventDefault();fileDrop.classList.remove('is-dragging');await loadPlaylistFile(e.dataTransfer?.files?.[0]);});
  els.channelSearch.addEventListener('input',renderChannels); els.favoriteCurrent.addEventListener('click',()=>state.selectedChannel&&toggleFavorite(state.selectedChannel));
  els.channelList.addEventListener('keydown',e=>{
    if(!['ArrowDown','ArrowUp','Enter'].includes(e.key))return;
    const rows=[...els.channelList.querySelectorAll('.channel-row')]; if(!rows.length)return;
    const current=rows.indexOf(document.activeElement);
    if(e.key==='Enter' && current>=0){e.preventDefault();rows[current].click();return;}
    if(e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault(); const step=e.key==='ArrowDown'?1:-1; const next=current<0?0:Math.max(0,Math.min(rows.length-1,current+step)); rows[next].focus(); rows[next].scrollIntoView({block:'nearest'});
    }
  });
  els.replaceButton.addEventListener('click',()=>{destroyHls();els.video.pause();els.tvApp.hidden=true;els.welcome.hidden=false;window.scrollTo({top:0,behavior:'smooth'});});
  els.epgButton.addEventListener('click',()=>els.epgDialog.showModal());
  els.loadEpgUrl.addEventListener('click',async()=>{hideError(els.epgError);const url=els.epgUrl.value.trim();if(!url)return;els.loadEpgUrl.disabled=true;try{await loadEpgFromText(await fetchText(url,'epg'));}catch(e){showError(els.epgError,proxyErrorMessage(e));}finally{els.loadEpgUrl.disabled=false;}});
  els.epgFile.addEventListener('change',async()=>{const f=els.epgFile.files?.[0];if(f)await loadEpgFromText(await f.text());els.epgFile.value='';});
  els.video.addEventListener('error',()=>showPlayerMessage(t('streamFail')));
  document.addEventListener('keydown',e=>{if(e.key==='/' && document.activeElement?.tagName!=='INPUT'){e.preventDefault();els.channelSearch.focus();}if((e.key==='f'||e.key==='F')&&state.selectedChannel&&document.activeElement?.tagName!=='INPUT'){toggleFavorite(state.selectedChannel);}});
  setInterval(()=>{if(state.selectedChannel)updateProgramme();},30000);

  applyLanguage(state.lang);
  console.info(`Cloud247 TV v${VERSION}`);
})();
