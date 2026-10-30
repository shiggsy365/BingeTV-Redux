// The page's shared helpers: state, requests ($, api, run, toast, esc), pairing and tabs.
// Loaded first; the other scripts use these when they're called.

let state = null, categories = {}, selectedGroup = null, channels = [], linkTargets = [];
let chHideHidden = false;
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

// Network failures (the Stick asleep or out of Wi-Fi range) show one banner instead of a
// toast per request; it clears on the next request that gets through.
class OfflineError extends Error {}
// A request the TV refused because this browser isn't paired: the PIN screen is showing.
class PairError extends OfflineError {}
let offline = false;
function setOffline(value) {
  if (value === offline) return;
  offline = value;
  $('offline').classList.toggle('show', value);
  if (!value) toast('Reconnected to the Fire Stick');
}
// The profile whose addons and catalogs the Addons and Catalogs tabs show (null: the TV's).
let addonProfile = null;

/**
 * Fills the profile picker at the top: whose addons, catalogs and settings the page shows
 * (the profile in use on the TV until another is picked).
 */
async function loadAddonProfiles() {
  const saved = addonProfile;
  addonProfile = null;
  const data = await api('/api/profiles');
  const ids = data.profiles.map(p => p.id);
  addonProfile = ids.includes(saved) ? saved : (data.profiles.find(p => p.current) || data.profiles[0]).id;
  settingsProfile = addonProfile;
  const pick = $('profilePick');
  pick.innerHTML = data.profiles.map(p =>
      `<option value="${esc(p.id)}">${esc(p.name)}${p.current ? ' (on the TV)' : ''}</option>`).join('');
  pick.value = addonProfile;
  const name = data.profiles.find(p => p.id === addonProfile).name;
  document.querySelectorAll('[data-profile-name]').forEach(el => { el.textContent = name; });
  pick.onchange = () => {
    addonProfile = settingsProfile = pick.value;
    const picked = data.profiles.find(p => p.id === addonProfile).name;
    document.querySelectorAll('[data-profile-name]').forEach(el => { el.textContent = picked; });
    openTab(currentTab);
  };
}

async function api(path, body) {
  if (addonProfile && /^\/api\/(addons|catalogs|hubs)/.test(path)) {
    path += (path.includes('?') ? '&' : '?') + 'profile=' + encodeURIComponent(addonProfile);
  }
  let res;
  try {
    res = await fetch(path, body === undefined ? {} :
        {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  } catch (e) {
    setOffline(true);
    throw new OfflineError();
  }
  setOffline(false);
  if (res.status === 401) { showPair(); throw new PairError(); }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

// ---- Pairing ----
function showPair() {
  if (!$('pair').hidden) return;
  $('pair').hidden = false;
  $('pairError').textContent = '';
  $('pairPin').value = '';
  $('pairPin').focus();
}
async function sendPin(pin) {
  const res = await fetch('/api/pair', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({pin})});
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || res.statusText);
  }
}
$('pairForm').onsubmit = e => {
  e.preventDefault();
  const pin = $('pairPin').value.trim();
  if (!/^\d{4}$/.test(pin)) { $('pairError').textContent = 'The PIN is 4 digits'; return; }
  sendPin(pin).then(() => {
    $('pair').hidden = true;
    start();
  }).catch(err => { $('pairError').textContent = err.message; $('pairPin').select(); });
};
$('pairPin').oninput = e => {
  e.target.value = e.target.value.replace(/\D/g, '').slice(0, 4);
  if (e.target.value.length === 4) $('pairForm').requestSubmit();
};
function toast(msg) {
  const t = $('toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.remove('show'), 3000);
}
async function run(fn) {
  try { await fn(); } catch (e) { if (!(e instanceof OfflineError)) toast(e.message); }
}

const TABS = ['addons', 'catalogs', 'playback', 'home', 'mdblist', 'radio', 'podcasts', 'profiles',
  'accounts', 'groups', 'logos', 'guides', 'backup'];
let currentTab = 'addons';

/** Shows a section and loads what it needs (for the profile picked at the top). */
function openTab(tab) {
  currentTab = tab;
  document.querySelectorAll('nav button[data-tab]').forEach(x => x.classList.toggle('active', x.dataset.tab === tab));
  TABS.forEach(t => $('tab-' + t).hidden = t !== tab);
  try { localStorage.setItem('tab', tab); } catch (e) { /* not kept */ }
  if (tab === 'addons') run(loadAddons);
  if (tab === 'catalogs') run(loadCatalogs);
  if (['playback', 'home', 'mdblist', 'profiles'].includes(tab)) run(loadProfiles);
  // The settings first: the podcast sync's status goes into them.
  if (tab === 'radio') { initAudio(); run(async () => { await loadProfiles(); await loadRadio(); }); }
  if (tab === 'podcasts') { initAudio(); run(async () => { await loadProfiles(); await loadPodcasts(); }); }
  if (tab === 'logos') openLogos();
  if (tab === 'backup') $('prefMergeUpConfirm').checked = !pref('skipMergeUpConfirm');
}
document.querySelectorAll('nav button[data-tab]').forEach(b => b.onclick = () => openTab(b.dataset.tab));
$('prefMergeUpConfirm').onchange = e => setPref('skipMergeUpConfirm', !e.target.checked);

function applyState(s) {
  state = s;
  renderSync(); renderGroups(); renderGuides(); renderSettings(); renderSources();
}
function renderSync() {
  const s = state.sync;
  const last = s.last ? 'Last synced ' + new Date(s.last).toLocaleString() : 'Not synced yet';
  $('syncStatus').textContent = s.running ? (s.status || 'Syncing…') : (s.status ? s.status + ' · ' : '') + last;
  $('syncBtn').disabled = s.running;
}
