// Profiles tab: the profiles, and each one's settings. Loaded before the page's main script,
// whose helpers ($, api, run, toast, esc) these functions use.

let profileData = null;
let settingsProfile = null;
let profilesReady = false;

// Each profile's settings, as the form shows them.
const PROFILE_SETTINGS = [
  {title: 'Streams', help: 'Which streams are offered, and in what order.', fields: [
    {key: 'sort', label: 'Order', type: 'select', options: [[0, 'Best quality first'], [1, 'Biggest first'], [2, 'Addon order']]},
    {key: 'maxResolution', label: 'Highest resolution', type: 'select', options: [[0, 'Any'], [2160, '4K'], [1080, '1080p'], [720, '720p']]},
    {key: 'hideCam', label: 'Hide cinema recordings (CAM, TS)', type: 'check'},
    {key: 'exclude', label: 'Hide streams mentioning', type: 'text', placeholder: 'e.g. french, hindi, 3D'},
  ]},
  {title: 'Playing', fields: [
    {key: 'playButton', label: 'Play', type: 'select', options: [['auto', 'Plays the best stream'], ['choose', 'Lists the streams to choose from']]},
    {key: 'autoplayNext', label: 'Up Next: play the next episode automatically', type: 'check'},
    {key: 'watchedPercent', label: 'Counts as watched at (%)', type: 'number', min: 50, max: 100},
  ]},
  {title: 'Languages', help: 'The player picks the first audio and subtitle tracks in these languages (codes or names, best first, e.g. "English, French" or "en, fr"). Empty means the TV\'s language. Subtitles built into the video are preferred; otherwise your subtitle addons\' are used.', fields: [
    {key: 'audioLanguages', label: 'Audio languages', type: 'text', placeholder: 'e.g. English, Japanese'},
    {key: 'subtitleLanguages', label: 'Subtitle languages', type: 'text', placeholder: 'e.g. eng, spa'},
    {key: 'subtitlesOn', label: 'Show subtitles when a video starts', type: 'check'},
  ]},
  {title: 'Home rows', help: 'Rows at the top of Home, before the Home hub\'s catalogs.', fields: [
    {key: 'rowContinue', label: 'Continue Watching', type: 'check'},
    {key: 'continueWithNextUp', label: 'Continue Watching includes the next episode of shows you\'re watching (one row; Next Up is then left out)', type: 'check'},
    {key: 'rowNextUp', label: 'Next Up (the next episode of shows you\'re watching)', type: 'check'},
    {key: 'rowWatchlist', label: 'Watchlist (MDBList)', type: 'check'},
    {key: 'rowOnNow', label: 'On Now (live TV)', type: 'check'},
  ]},
  {title: 'Trailers', fields: [
    {key: 'homeTrailers', label: 'Play trailers behind the details on Home', type: 'check'},
    {key: 'trailerDelay', label: 'After (seconds on a title)', type: 'number', min: 1, max: 30},
    {key: 'trailerMuted', label: 'Without sound', type: 'check'},
  ]},
  {title: 'MDBList', help: 'Your API key from mdblist.com (Preferences → API). It syncs what you watch both ways, tells MDBList what\'s playing, and gives Home a watchlist row.', mdblist: true, fields: [
    {key: 'mdblistKey', label: 'API key', type: 'text', placeholder: 'Your MDBList API key'},
    {key: 'mdblistScrobble', label: 'Tell MDBList what\'s playing (scrobble)', type: 'check'},
    {key: 'mdblistSync', label: 'Sync watched history every few hours', type: 'check'},
  ]},
];

let avatarTarget = null;

/** A profile's picture: square-cropped and shrunk on the TV. */
async function uploadAvatar(id, file) {
  if (!file || !file.type.startsWith('image/')) { toast("That isn't an image file"); return; }
  await run(async () => {
    const res = await fetch('/api/profiles/avatar?id=' + encodeURIComponent(id),
        {method: 'POST', headers: {'Content-Type': file.type}, body: file});
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || res.statusText);
    toast('Picture saved');
    await loadProfiles();
  });
}

function initProfiles() {
  if (profilesReady) return;
  profilesReady = true;
  $('avatarFile').onchange = e => { if (avatarTarget) uploadAvatar(avatarTarget, e.target.files[0]); };
  $('profileAdd').onclick = () => run(async () => {
    const name = $('profileNew').value.trim();
    if (!name) { toast('Name the profile first'); return; }
    showProfiles(await api('/api/profiles', {name}));
    $('profileNew').value = '';
    toast('Added ' + name);
  });
  $('profileList').onchange = e => {
    if (!e.target.matches('input[data-rename]')) return;
    const id = e.target.closest('[data-id]').dataset.id;
    run(async () => { showProfiles(await api('/api/profiles/update', {id, name: e.target.value.trim()})); toast('Renamed'); });
  };
  $('profileList').onclick = e => {
    const btn = e.target.closest('button');
    if (!btn) return;
    const id = btn.closest('[data-id]').dataset.id;
    const p = profileData.profiles.find(x => x.id === id);
    if (btn.dataset.act === 'edit') { settingsProfile = id; renderProfileSettings(); }
    if (btn.dataset.act === 'picture') { avatarTarget = id; $('avatarFile').value = ''; $('avatarFile').click(); }
    if (btn.dataset.act === 'nopicture') run(async () => showProfiles(await api('/api/profiles/avatar/delete', {id})));
    if (btn.dataset.act === 'remove' && confirm('Remove ' + p.name + ', with its watch history and settings?')) {
      run(async () => showProfiles(await api('/api/profiles/delete', {id})));
    }
  };
  $('profileSettings').onchange = e => {
    const el = e.target;
    const key = el.dataset.key;
    if (!key) return;
    const value = el.type === 'checkbox' ? el.checked
        : el.type === 'number' || el.dataset.numeric ? Number(el.value) : el.value.trim();
    run(async () => { showProfiles(await api('/api/profiles/update', {id: settingsProfile, settings: {[key]: value}})); toast('Saved'); });
  };
  $('profileSettings').onclick = e => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    if (btn.dataset.act === 'mdbtest') {
      run(async () => toast((await api('/api/mdblist/test', {id: settingsProfile})).message));
    } else if (btn.dataset.act === 'mdbsync') {
      btn.disabled = true;
      run(async () => {
        try { const r = await api('/api/mdblist/sync', {id: settingsProfile}); toast(r.profile + ': ' + r.message); }
        finally { btn.disabled = false; }
      });
    }
  };
}

async function loadProfiles() {
  showProfiles(await api('/api/profiles'));
}

function showProfiles(data) {
  profileData = data;
  if (!data.profiles.some(p => p.id === settingsProfile)) {
    settingsProfile = (data.profiles.find(p => p.current) || data.profiles[0]).id;
  }
  const many = data.profiles.length > 1;
  $('profileList').innerHTML = data.profiles.map(p => `
    <div class="catrow" data-id="${esc(p.id)}">
      <button class="avatar" data-act="picture" title="Upload a picture">${p.avatar
          ? `<img src="${esc(p.avatar)}" alt="">` : esc((p.name || '?').charAt(0).toUpperCase())}</button>
      <div class="name"><input type="text" data-rename value="${esc(p.name)}"></div>
      <div class="src muted">${p.current ? 'In use on the TV' : ''}</div>
      <button class="btn${p.id === settingsProfile ? ' primary' : ''}" data-act="edit">Settings</button>
      ${p.avatar ? '<button class="btn" data-act="nopicture">Remove picture</button>' : ''}
      ${many ? '<button class="btn danger" data-act="remove">Remove</button>' : ''}
    </div>`).join('');
  renderProfileSettings();
}

function renderProfileSettings() {
  const p = profileData.profiles.find(x => x.id === settingsProfile);
  const s = p.settings;
  $('profileSettings').innerHTML = `<h2 style="margin:18px 0 8px">Settings for ${esc(p.name)}</h2>` +
    PROFILE_SETTINGS.map(section => `
      <div class="panel">
        <h2>${esc(section.title)}</h2>
        ${section.help ? `<p class="muted">${esc(section.help)}</p>` : ''}
        ${section.fields.map(f => fieldHtml(f, s[f.key])).join('')}
        ${section.mdblist ? `<div class="row" style="margin-top:8px">
            <button class="btn" data-act="mdbtest">Test the key</button>
            <button class="btn" data-act="mdbsync">Sync now</button>
          </div>` : ''}
      </div>`).join('');
  document.querySelectorAll('#profileSettings select').forEach(el => { el.value = String(s[el.dataset.key]); });
}

function fieldHtml(f, value) {
  if (f.type === 'check') {
    return `<label class="row" style="margin-bottom:8px"><input type="checkbox" data-key="${f.key}"${value ? ' checked' : ''}> ${esc(f.label)}</label>`;
  }
  let input;
  if (f.type === 'select') {
    const numeric = typeof f.options[0][0] === 'number';
    input = `<select data-key="${f.key}"${numeric ? ' data-numeric="1"' : ''}>${f.options.map(([v, l]) => `<option value="${esc(v)}">${esc(l)}</option>`).join('')}</select>`;
  } else if (f.type === 'number') {
    input = `<input type="number" data-key="${f.key}" value="${esc(value)}" min="${f.min}" max="${f.max}" style="width:100px">`;
  } else {
    input = `<input type="text" data-key="${f.key}" value="${esc(value)}" placeholder="${esc(f.placeholder || '')}" autocomplete="off">`;
  }
  return `<label class="field"><span>${esc(f.label)}</span>${input}</label>`;
}
