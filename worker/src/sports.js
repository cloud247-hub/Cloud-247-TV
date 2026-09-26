const API_FOOTBALL_BASE = 'https://v3.football.api-sports.io';
const TENNIS_BASE = 'https://api.livetennisapi.com/api/public/v1';
const ESPN_GOLF_BASE = 'https://site.api.espn.com/apis/site/v2/sports/golf';

const FOOTBALL_TEAM_LIMIT = 8;
const FOOTBALL_LEAGUE_LIMIT = 6;
const TENNIS_PLAYER_LIMIT = 12;
const GOLF_PLAYER_LIMIT = 12;

const TENNIS_MAJORS = [
  ['australian open', 'Australian Open'],
  ['roland garros', 'Roland-Garros'],
  ['french open', 'Roland-Garros'],
  ['wimbledon', 'Wimbledon'],
  ['us open', 'US Open'],
];

const GOLF_MAJORS = [
  ['masters tournament', 'Masters'],
  ['the masters', 'Masters'],
  ['pga championship', 'PGA Championship'],
  ['u.s. open', 'U.S. Open'],
  ['us open', 'U.S. Open'],
  ['the open championship', 'The Open'],
  ['british open', 'The Open'],
];

const GOLF_TOURS = {
  pga: 'PGA Tour',
  eur: 'DP World Tour',
  lpga: 'LPGA',
  liv: 'LIV Golf',
};

export async function handleSportsUpcoming(input, env, headers) {
  const config = normalizeConfig(input);
  if (!config.hasFavorites) {
    return jsonResponse({
      events: [],
      sources: emptySourceStatus(env),
      generated_at: new Date().toISOString(),
    }, 200, headers);
  }

  const from = isoDate(new Date());
  const to = isoDate(new Date(Date.now() + 14 * 86400000));

  const [football, tennis, golf] = await Promise.all([
    collectFootball(config, env, from, to),
    collectTennis(config, env),
    collectGolf(config, from, to),
  ]);

  const events = [...football.events, ...tennis.events, ...golf.events]
    .filter((event) => event.start_ms >= Date.now() - 30 * 60000)
    .sort((a, b) => a.start_ms - b.start_ms)
    .filter((event, index, all) =>
      all.findIndex((candidate) => candidate.id === event.id && candidate.sport === event.sport) === index
    )
    .slice(0, 80);

  return jsonResponse({
    events,
    sources: {
      football: football.status,
      tennis: tennis.status,
      golf: golf.status,
    },
    generated_at: new Date().toISOString(),
  }, 200, headers);
}

function normalizeConfig(input) {
  const names = (value, limit) => Array.isArray(value)
    ? value.map((item) => String(item || '').trim()).filter((item) => item.length >= 2).slice(0, limit)
    : [];

  const golfTours = Array.isArray(input.golf_tours)
    ? input.golf_tours.map((item) => String(item || '').toLowerCase()).filter((item) => item in GOLF_TOURS)
    : [];

  const footballTeams = names(input.football_teams, FOOTBALL_TEAM_LIMIT);
  const footballLeagues = names(input.football_leagues, FOOTBALL_LEAGUE_LIMIT);
  const tennisPlayers = names(input.tennis_players, TENNIS_PLAYER_LIMIT);
  const golfPlayers = names(input.golf_players, GOLF_PLAYER_LIMIT);

  return {
    footballTeams,
    footballLeagues,
    tennisPlayers,
    golfPlayers,
    premierLeague: Boolean(input.premier_league),
    tennisMajors: Boolean(input.tennis_majors),
    golfMajors: Boolean(input.golf_majors),
    golfTours,
    hasFavorites: Boolean(
      footballTeams.length ||
      footballLeagues.length ||
      tennisPlayers.length ||
      golfPlayers.length ||
      input.premier_league ||
      input.tennis_majors ||
      input.golf_majors ||
      golfTours.length
    ),
  };
}

function emptySourceStatus(env) {
  return {
    football: sourceStatus(Boolean(env.API_FOOTBALL_KEY), env.API_FOOTBALL_KEY ? 'API-Football' : 'API_FOOTBALL_KEY mangler'),
    tennis: sourceStatus(Boolean(env.LIVE_TENNIS_API_KEY), env.LIVE_TENNIS_API_KEY ? 'Live Tennis API' : 'LIVE_TENNIS_API_KEY mangler'),
    golf: sourceStatus(true, 'ESPN Golf'),
  };
}

async function collectFootball(config, env, from, to) {
  if (!env.API_FOOTBALL_KEY) {
    return { events: [], status: sourceStatus(false, 'API_FOOTBALL_KEY mangler') };
  }

  try {
    const jobs = [];

    for (const teamName of config.footballTeams) {
      jobs.push((async () => {
        const team = await resolveFootballTeam(teamName, env);
        if (!team) return [];
        const payload = await footballGet(
          env,
          `/fixtures?team=${team.id}&next=12`,
          `team-fixtures/${team.id}`,
          60 * 60
        );
        return footballFixtures(payload, 'football_team', teamName, from, to);
      })());
    }

    for (const leagueName of config.footballLeagues) {
      jobs.push((async () => {
        const league = await resolveFootballLeague(leagueName, env);
        if (!league) return [];
        const payload = await footballGet(
          env,
          `/fixtures?league=${league.id}&season=${league.season}&from=${from}&to=${to}`,
          `league-fixtures/${league.id}/${league.season}/${from}/${to}`,
          60 * 60
        );
        return footballFixtures(payload, 'football_league', leagueName, from, to);
      })());
    }

    if (config.premierLeague) {
      jobs.push((async () => {
        const league = await resolveFootballLeagueById(39, env);
        if (!league) return [];
        const payload = await footballGet(
          env,
          `/fixtures?league=39&season=${league.season}&from=${from}&to=${to}`,
          `league-fixtures/39/${league.season}/${from}/${to}`,
          60 * 60
        );
        return footballFixtures(payload, 'premier_league', 'Premier League', from, to);
      })());
    }

    const batches = await Promise.all(jobs);
    return {
      events: batches.flat(),
      status: sourceStatus(true, 'API-Football'),
    };
  } catch (error) {
    return {
      events: [],
      status: sourceStatus(false, `API-Football: ${safeError(error)}`),
    };
  }
}

async function resolveFootballTeam(name, env) {
  const payload = await footballGet(
    env,
    `/teams?search=${encodeURIComponent(name)}`,
    `team-search/${cachePart(name)}`,
    7 * 24 * 60 * 60
  );
  const rows = Array.isArray(payload?.response) ? payload.response : [];
  const exact = rows.find((row) => normalize(row?.team?.name) === normalize(name));
  const row = exact || rows[0];
  const id = Number(row?.team?.id || 0);
  return id ? { id, name: String(row?.team?.name || name) } : null;
}

async function resolveFootballLeague(name, env) {
  const payload = await footballGet(
    env,
    `/leagues?search=${encodeURIComponent(name)}&current=true`,
    `league-search/${cachePart(name)}`,
    24 * 60 * 60
  );
  return leagueRow(payload, name);
}

async function resolveFootballLeagueById(id, env) {
  const payload = await footballGet(
    env,
    `/leagues?id=${id}&current=true`,
    `league-id/${id}`,
    24 * 60 * 60
  );
  return leagueRow(payload, String(id));
}

function leagueRow(payload, preferredName) {
  const rows = Array.isArray(payload?.response) ? payload.response : [];
  const exact = rows.find((row) => normalize(row?.league?.name) === normalize(preferredName));
  const row = exact || rows[0];
  const id = Number(row?.league?.id || 0);
  const seasons = Array.isArray(row?.seasons) ? row.seasons : [];
  const current = seasons.find((season) => season?.current) || seasons[seasons.length - 1];
  const season = Number(current?.year || 0);
  return id && season ? { id, season, name: String(row?.league?.name || preferredName) } : null;
}

function footballFixtures(payload, matchType, matchName, from, to) {
  const min = Date.parse(`${from}T00:00:00Z`);
  const max = Date.parse(`${to}T23:59:59Z`);
  const rows = Array.isArray(payload?.response) ? payload.response : [];

  return rows.map((row) => {
    const start = Date.parse(String(row?.fixture?.date || ''));
    if (!Number.isFinite(start) || start < min || start > max) return null;

    const home = String(row?.teams?.home?.name || '').trim();
    const away = String(row?.teams?.away?.name || '').trim();
    const title = [home, away].filter(Boolean).join(' – ');
    if (!title) return null;

    return {
      id: `football:${row?.fixture?.id || title + ':' + start}`,
      sport: 'football',
      title,
      competition: String(row?.league?.name || '').trim(),
      start_ms: start,
      start_at: new Date(start).toISOString(),
      participants: [home, away].filter(Boolean),
      match_type: matchType,
      match_name: matchName,
      source: 'API-Football',
    };
  }).filter(Boolean);
}

async function footballGet(env, path, cacheKey, ttlSeconds) {
  return cachedJson(`football/${cacheKey}`, ttlSeconds, async () => {
    const response = await fetch(API_FOOTBALL_BASE + path, {
      headers: {
        'Accept': 'application/json',
        'x-apisports-key': String(env.API_FOOTBALL_KEY),
        'User-Agent': 'Cloud247-TV-Sports/1.5.0',
      },
    });
    if (!response.ok) throw new Error(`http_${response.status}`);
    const payload = await response.json();
    if (Array.isArray(payload?.errors) && payload.errors.length) {
      throw new Error('api_error');
    }
    if (payload?.errors && typeof payload.errors === 'object' && Object.keys(payload.errors).length) {
      throw new Error('api_error');
    }
    return payload;
  });
}

async function collectTennis(config, env) {
  if (!env.LIVE_TENNIS_API_KEY) {
    return { events: [], status: sourceStatus(false, 'LIVE_TENNIS_API_KEY mangler') };
  }

  if (!config.tennisPlayers.length && !config.tennisMajors) {
    return { events: [], status: sourceStatus(true, 'Live Tennis API') };
  }

  try {
    const [atp, wta] = await Promise.all([
      tennisFixtures('atp', env),
      tennisFixtures('wta', env),
    ]);
    const rows = [...atp, ...wta];
    const events = [];

    for (const row of rows) {
      const p1 = String(row?.player1_name || '').trim();
      const p2 = String(row?.player2_name || '').trim();
      const tournament = String(row?.tournament || '').trim();
      const start = tennisStart(row);
      if (!Number.isFinite(start) || start < Date.now() - 30 * 60000) continue;

      const combined = normalize([p1, p2, tournament].join(' '));
      let matchType = '';
      let matchName = '';

      for (const player of config.tennisPlayers) {
        if (combined.includes(normalize(player))) {
          matchType = 'tennis_player';
          matchName = player;
          break;
        }
      }

      if (!matchType && config.tennisMajors) {
        const major = TENNIS_MAJORS.find(([needle]) => combined.includes(needle));
        if (major) {
          matchType = 'tennis_major';
          matchName = major[1];
        }
      }

      if (!matchType) continue;
      events.push({
        id: `tennis:${row?.id || p1 + ':' + p2 + ':' + start}`,
        sport: 'tennis',
        title: [p1, p2].filter(Boolean).join(' – ') || tournament,
        competition: tournament,
        start_ms: start,
        start_at: new Date(start).toISOString(),
        participants: [p1, p2].filter(Boolean),
        match_type: matchType,
        match_name: matchName,
        source: 'Live Tennis API',
      });
    }

    return { events, status: sourceStatus(true, 'Live Tennis API') };
  } catch (error) {
    return { events: [], status: sourceStatus(false, `Live Tennis API: ${safeError(error)}`) };
  }
}

async function tennisFixtures(tour, env) {
  return cachedJson(`tennis/fixtures/${tour}`, 2 * 60 * 60, async () => {
    const response = await fetch(`${TENNIS_BASE}/fixtures?tour=${tour}&limit=100`, {
      headers: {
        'Accept': 'application/json',
        'X-API-Key': String(env.LIVE_TENNIS_API_KEY),
        'User-Agent': 'Cloud247-TV-Sports/1.5.0',
      },
    });
    if (!response.ok) throw new Error(`http_${response.status}`);
    const payload = await response.json();
    return Array.isArray(payload?.data) ? payload.data : [];
  });
}

function tennisStart(row) {
  const startTime = String(row?.start_time || '').trim();
  if (startTime) {
    const direct = Date.parse(startTime);
    if (Number.isFinite(direct)) return direct;
  }

  const date = String(row?.event_date || '').trim();
  if (!date) return NaN;
  const combined = startTime && /^\d{1,2}:\d{2}/.test(startTime)
    ? `${date}T${startTime.replace(/Z$/, '')}Z`
    : `${date}T00:00:00Z`;
  return Date.parse(combined);
}

async function collectGolf(config, from, to) {
  const wantsGolf = config.golfPlayers.length || config.golfMajors || config.golfTours.length;
  if (!wantsGolf) return { events: [], status: sourceStatus(true, 'ESPN Golf') };

  try {
    const tours = config.golfTours.length
      ? [...new Set(config.golfTours)]
      : ['pga', 'eur', 'lpga', 'liv'];

    const payloads = await Promise.all(
      tours.map(async (tour) => ({
        tour,
        payload: await espnGolfScoreboard(tour, from, to),
      }))
    );

    const events = [];
    for (const { tour, payload } of payloads) {
      const rows = Array.isArray(payload?.events) ? payload.events : [];
      for (const row of rows) {
        const title = String(row?.name || row?.shortName || '').trim();
        const competition = String(row?.season?.name || GOLF_TOURS[tour] || tour).trim();
        const start = Date.parse(String(row?.date || row?.competitions?.[0]?.date || ''));
        if (!title || !Number.isFinite(start) || start < Date.now() - 12 * 60 * 60 * 1000) continue;

        const participants = golfParticipants(row);
        const combined = normalize([title, competition, ...participants].join(' '));
        let matchType = '';
        let matchName = '';

        for (const player of config.golfPlayers) {
          if (combined.includes(normalize(player))) {
            matchType = 'golf_player';
            matchName = player;
            break;
          }
        }

        if (!matchType && config.golfMajors) {
          const major = GOLF_MAJORS.find(([needle]) => combined.includes(needle));
          if (major) {
            matchType = 'golf_major';
            matchName = major[1];
          }
        }

        if (!matchType && config.golfTours.includes(tour)) {
          matchType = 'golf_tour';
          matchName = GOLF_TOURS[tour] || tour.toUpperCase();
        }

        if (!matchType) continue;
        events.push({
          id: `golf:${tour}:${row?.id || title + ':' + start}`,
          sport: 'golf',
          title,
          competition: GOLF_TOURS[tour] || competition,
          start_ms: start,
          start_at: new Date(start).toISOString(),
          participants,
          match_type: matchType,
          match_name: matchName,
          source: 'ESPN Golf',
        });
      }
    }

    return { events, status: sourceStatus(true, 'ESPN Golf') };
  } catch (error) {
    return { events: [], status: sourceStatus(false, `ESPN Golf: ${safeError(error)}`) };
  }
}

async function espnGolfScoreboard(tour, from, to) {
  const start = from.replaceAll('-', '');
  const end = to.replaceAll('-', '');
  return cachedJson(`golf/${tour}/${start}/${end}`, 60 * 60, async () => {
    const url = `${ESPN_GOLF_BASE}/${tour}/scoreboard?dates=${start}-${end}&limit=100`;
    const response = await fetch(url, {
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'Cloud247-TV-Sports/1.5.0',
      },
    });
    if (!response.ok) throw new Error(`http_${response.status}`);
    return response.json();
  });
}

function golfParticipants(row) {
  const competitors = Array.isArray(row?.competitions?.[0]?.competitors)
    ? row.competitions[0].competitors
    : [];

  return competitors
    .map((competitor) =>
      String(
        competitor?.athlete?.displayName ||
        competitor?.athlete?.fullName ||
        competitor?.displayName ||
        ''
      ).trim()
    )
    .filter(Boolean)
    .slice(0, 200);
}

async function cachedJson(key, ttlSeconds, loader) {
  const cache = caches.default;
  const cacheUrl = new URL('https://sports-cache.cloud247.no/');
  cacheUrl.pathname += key.split('/').map(encodeURIComponent).join('/');
  const request = new Request(cacheUrl.toString(), { method: 'GET' });
  const hit = await cache.match(request);
  if (hit) return hit.json();

  const payload = await loader();
  const response = new Response(JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': `public, max-age=${ttlSeconds}`,
    },
  });
  await cache.put(request, response.clone());
  return payload;
}

function sourceStatus(ok, detail) {
  return { ok, detail };
}

function jsonResponse(value, status, extraHeaders) {
  const headers = new Headers(extraHeaders || {});
  headers.set('Content-Type', 'application/json; charset=utf-8');
  headers.set('Cache-Control', 'no-store');
  headers.set('X-Content-Type-Options', 'nosniff');
  return new Response(JSON.stringify(value), { status, headers });
}

function normalize(value) {
  return String(value || '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9æøå -]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function cachePart(value) {
  return normalize(value).replace(/\s+/g, '-').slice(0, 80) || 'unknown';
}

function isoDate(date) {
  return date.toISOString().slice(0, 10);
}

function safeError(error) {
  return String(error?.message || 'ukjent feil').replace(/[^a-zA-Z0-9_ .:-]/g, '').slice(0, 100);
}
