// Radio and Podcasts: the picked profile's favourite stations and podcast subscriptions, with
// searches to add more. Their settings (country, sync server, Podcast Index key) are on the
// same pages, from profiles.js. Loaded before the page's main script, whose helpers ($, api,
// run, toast, esc) these functions use.

let radioFavourites = [];
let radioFound = [];
let podcastFound = [];
let audioReady = false;

function initAudio() {
  if (audioReady) return;
  audioReady = true;
  const radioSearch = () => run(async () => {
    const q = $('radioQuery').value.trim();
    if (q.length < 2) { toast('Type at least two letters'); return; }
    $('radioResults').innerHTML = '<p class="muted">Searching…</p>';
    radioFound = (await api('/api/radio/search?q=' + encodeURIComponent(q))).stations;
    showRadioResults();
  });
  $('radioSearch').onclick = radioSearch;
  $('radioQuery').onkeydown = e => { if (e.key === 'Enter') radioSearch(); };
  $('radioResults').onclick = e => {
    const btn = e.target.closest('button[data-add]');
    if (!btn) return;
    const station = radioFound[Number(btn.dataset.add)];
    if (radioFavourites.some(f => f.id === station.id)) { toast('Already a favourite'); return; }
    saveFavourites([...radioFavourites, station], 'Added ' + station.title);
  };
  $('radioFavourites').onclick = e => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const i = Number(btn.closest('[data-i]').dataset.i);
    const list = [...radioFavourites];
    if (btn.dataset.act === 'up' && i > 0) [list[i - 1], list[i]] = [list[i], list[i - 1]];
    if (btn.dataset.act === 'down' && i < list.length - 1) [list[i + 1], list[i]] = [list[i], list[i + 1]];
    if (btn.dataset.act === 'remove') list.splice(i, 1);
    saveFavourites(list, btn.dataset.act === 'remove' ? 'Removed' : 'Moved');
  };

  const podcastSearch = () => run(async () => {
    const q = $('podcastQuery').value.trim();
    if (q.length < 2) { toast('Type at least two letters'); return; }
    $('podcastResults').innerHTML = '<p class="muted">Searching…</p>';
    podcastFound = (await api('/api/podcasts/search?id=' + encodeURIComponent(settingsProfile)
        + '&q=' + encodeURIComponent(q))).podcasts;
    showPodcastResults();
  });
  $('podcastSearch').onclick = podcastSearch;
  $('podcastQuery').onkeydown = e => { if (e.key === 'Enter') podcastSearch(); };
  $('podcastResults').onclick = e => {
    const btn = e.target.closest('button[data-add]');
    if (btn) subscribe(podcastFound[Number(btn.dataset.add)].feedUrl, btn);
  };
  $('podcastAdd').onclick = () => {
    const url = $('podcastUrl').value.trim();
    if (!url) { toast('Paste the feed URL first'); return; }
    subscribe(url, $('podcastAdd'), () => { $('podcastUrl').value = ''; });
  };
  $('podcastList').onclick = e => {
    const btn = e.target.closest('button[data-remove]');
    if (!btn) return;
    const p = JSON.parse(btn.dataset.remove);
    if (!confirm('Unsubscribe from ' + p.title + '?')) return;
    run(async () => { showPodcasts(await api('/api/podcasts/remove', {id: settingsProfile, feedUrl: p.feedUrl})); toast('Unsubscribed'); });
  };
}

// ---- Radio

async function loadRadio() {
  radioFavourites = (await api('/api/radio?id=' + encodeURIComponent(settingsProfile))).favourites;
  showFavourites();
}

function saveFavourites(list, message) {
  run(async () => {
    radioFavourites = (await api('/api/radio/favourites', {id: settingsProfile, favourites: list})).favourites;
    showFavourites();
    showRadioResults();
    toast(message);
  });
}

function stationArt(s) {
  return s.artwork ? `<img src="${esc(s.artwork)}" alt="" loading="lazy" referrerpolicy="no-referrer"
      style="width:40px;height:40px;object-fit:contain;border-radius:6px;background:#fff1">`
      : '<span style="width:40px;height:40px;display:inline-block"></span>';
}

function showFavourites() {
  $('radioFavourites').innerHTML = radioFavourites.length ? radioFavourites.map((s, i) => `
    <div class="catrow" data-i="${i}">
      ${stationArt(s)}
      <div class="name"><b>${esc(s.title)}</b><div class="muted">${esc(s.subtitle)}</div></div>
      <button class="btn" data-act="up" title="Move up"${i === 0 ? ' disabled' : ''}>↑</button>
      <button class="btn" data-act="down" title="Move down"${i === radioFavourites.length - 1 ? ' disabled' : ''}>↓</button>
      <button class="btn danger" data-act="remove">Remove</button>
    </div>`).join('') : '<p class="muted">None yet. Find stations below, or press the heart while one plays on the TV.</p>';
}

function showRadioResults() {
  if (!radioFound.length) { $('radioResults').innerHTML = $('radioQuery').value ? '<p class="muted">No stations found.</p>' : ''; return; }
  $('radioResults').innerHTML = radioFound.map((s, i) => {
    const fav = radioFavourites.some(f => f.id === s.id);
    return `<div class="catrow">
      ${stationArt(s)}
      <div class="name"><b>${esc(s.title)}</b><div class="muted">${esc(s.subtitle)}</div></div>
      ${fav ? '<span class="muted">♥ Favourite</span>' : `<button class="btn" data-add="${i}">♥ Add</button>`}
    </div>`;
  }).join('');
}

// ---- Podcasts

async function loadPodcasts() {
  showPodcasts(await api('/api/podcasts?id=' + encodeURIComponent(settingsProfile)));
}

function subscribe(feedUrl, btn, then) {
  btn.disabled = true;
  run(async () => {
    try {
      const r = await api('/api/podcasts/add', {id: settingsProfile, feedUrl});
      showPodcasts(r);
      showPodcastResults();
      toast('Subscribed');
      if (then) then();
    } finally { btn.disabled = false; }
  });
}

let podcastSubs = [];

function showPodcasts(data) {
  podcastSubs = data.podcasts;
  const status = $('podSyncStatus');
  if (status) status.textContent = data.syncing ? data.sync.join(' · ') : '';
  $('podcastList').innerHTML = podcastSubs.length ? podcastSubs.map(p => `
    <div class="catrow">
      ${p.artwork ? `<img src="${esc(p.artwork)}" alt="" loading="lazy" referrerpolicy="no-referrer" style="width:40px;height:40px;object-fit:cover;border-radius:6px">` : ''}
      <div class="name"><b>${esc(p.title)}</b><div class="muted">${esc(p.author || p.feedUrl)}</div></div>
      <button class="btn danger" data-remove="${esc(JSON.stringify({feedUrl: p.feedUrl, title: p.title}))}">Unsubscribe</button>
    </div>`).join('') : '<p class="muted">None yet. Find podcasts below, or paste a feed URL.</p>';
}

function showPodcastResults() {
  if (!podcastFound.length) { $('podcastResults').innerHTML = $('podcastQuery').value ? '<p class="muted">No podcasts found.</p>' : ''; return; }
  $('podcastResults').innerHTML = podcastFound.map((p, i) => {
    const subscribed = podcastSubs.some(s => s.feedUrl === p.feedUrl);
    return `<div class="catrow">
      ${p.artwork ? `<img src="${esc(p.artwork)}" alt="" loading="lazy" referrerpolicy="no-referrer" style="width:40px;height:40px;object-fit:cover;border-radius:6px">` : ''}
      <div class="name"><b>${esc(p.title)}</b><div class="muted">${esc(p.author)}</div></div>
      ${subscribed ? '<span class="muted">✓ Subscribed</span>' : `<button class="btn" data-add="${i}">Subscribe</button>`}
    </div>`;
  }).join('');
}
