// Addons tab: Stremio addons, added by manifest URL. Loaded before the page's main script,
// whose helpers ($, api, run, toast, esc) these functions use when they're called.

let addons = [];
let addonsReady = false;

const RESOURCE_LABELS = {catalog: 'Catalogs', meta: 'Info', stream: 'Streams', subtitles: 'Subtitles'};

function initAddons() {
  if (addonsReady) return;
  addonsReady = true;
  $('addonAdd').onclick = () => run(async () => {
    const url = $('addonUrl').value.trim();
    if (!url) { toast('Paste an addon URL first'); return; }
    const btn = $('addonAdd');
    btn.disabled = true; btn.textContent = 'Adding…';
    try {
      const data = await api('/api/addons', {url});
      $('addonUrl').value = '';
      renderAddons(data.addons);
      toast('Added ' + data.addons[data.addons.length - 1].name);
    } finally { btn.disabled = false; btn.textContent = 'Add'; }
  });
  $('addonUrl').onkeydown = e => { if (e.key === 'Enter') $('addonAdd').click(); };
  $('addonRefreshAll').onclick = () => refreshAddons('');
  $('addonList').onclick = e => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const key = btn.closest('[data-key]').dataset.key;
    const addon = addons.find(a => a.key === key);
    const act = btn.dataset.act;
    if (act === 'up' || act === 'down') {
      run(async () => renderAddons((await api('/api/addons/update', {key, move: act === 'up' ? -1 : 1})).addons));
    } else if (act === 'refresh') {
      refreshAddons(key);
    } else if (act === 'remove') {
      if (confirm('Remove ' + addon.name + '?')) {
        run(async () => { renderAddons((await api('/api/addons/delete', {key})).addons); toast('Removed ' + addon.name); });
      }
    } else if (act === 'copy') {
      copyText(addon.url).then(ok => toast(ok ? 'URL copied' : 'Couldn\'t copy: select it in the box instead'));
      showUrlBox(key, false);
    } else if (act === 'newurl') {
      showUrlBox(key, true);
    } else if (act === 'saveurl') {
      const url = btn.closest('[data-key]').querySelector('.newurl input').value.trim();
      if (!url || url === addon.url) { showUrlBox(key, false); return; }
      run(async () => {
        renderAddons((await api('/api/addons/update', {key, url})).addons);
        toast('Updated ' + addon.name);
      });
    } else if (act === 'cancelurl') {
      showUrlBox(key, false);
    }
  };
  $('addonList').onchange = e => {
    if (!e.target.matches('input[data-act=enabled]')) return;
    const key = e.target.closest('[data-key]').dataset.key;
    run(async () => renderAddons((await api('/api/addons/update', {key, enabled: e.target.checked})).addons));
  };
}

async function loadAddons() {
  renderAddons((await api('/api/addons')).addons);
}

function refreshAddons(key) {
  run(async () => {
    const btn = $('addonRefreshAll');
    if (!key) { btn.disabled = true; btn.textContent = 'Refreshing…'; }
    try {
      const data = await api('/api/addons/refresh', {key});
      renderAddons(data.addons);
      toast(data.failed.length ? 'Couldn\'t refresh ' + data.failed.join('; ') : 'Refreshed');
    } finally { btn.disabled = false; btn.textContent = 'Refresh all'; }
  });
}

function renderAddons(list) {
  addons = list;
  const el = $('addonList');
  if (!list.length) {
    el.innerHTML = '<p class="muted">No addons yet. Add one above: for example Cinemeta, ' +
        'https://v3-cinemeta.strem.io/manifest.json, for catalogs and information.</p>';
    return;
  }
  el.innerHTML = list.map((a, i) => `
    <div class="addon${a.enabled ? '' : ' off'}" data-key="${esc(a.key)}">
      <div class="row">
        ${a.logo ? `<img class="addonlogo" src="${esc(a.logo)}" alt="" loading="lazy" referrerpolicy="no-referrer">`
                 : '<div class="addonlogo"></div>'}
        <div class="grow">
          <div><strong>${esc(a.name)}</strong> <span class="muted">${esc(a.version)}</span></div>
          <div class="muted ellipsis">${esc(a.host)}${a.description ? ' · ' + esc(a.description) : ''}</div>
        </div>
        <label class="row" title="Use this addon"><input type="checkbox" data-act="enabled"${a.enabled ? ' checked' : ''}> On</label>
      </div>
      <div class="row wrap tags">
        ${a.resources.filter(r => RESOURCE_LABELS[r]).map(r => `<span class="tag">${RESOURCE_LABELS[r]}</span>`).join('')}
        ${a.catalogs ? `<span class="tag">${a.catalogs} catalog${a.catalogs === 1 ? '' : 's'}</span>` : ''}
        ${a.searchCatalogs ? `<span class="tag">Search</span>` : ''}
        ${a.p2p ? `<span class="tag">Torrents (not played)</span>` : ''}
        <span class="muted">${esc(a.types.join(', '))}</span>
      </div>
      <div class="row wrap">
        <button class="btn" data-act="up"${i === 0 ? ' disabled' : ''} title="Move up">▲</button>
        <button class="btn" data-act="down"${i === list.length - 1 ? ' disabled' : ''} title="Move down">▼</button>
        ${a.configureUrl ? `<a class="btn" href="${esc(a.configureUrl)}" target="_blank" rel="noopener noreferrer">Configure</a>` : ''}
        <button class="btn" data-act="newurl">New URL</button>
        <button class="btn" data-act="copy">Copy URL</button>
        <button class="btn" data-act="refresh">Refresh</button>
        <button class="btn danger" data-act="remove">Remove</button>
      </div>
      <div class="newurl" hidden>
        <p class="muted">After changing its settings on its configure page, paste the new URL. The
          addon keeps its place and how its catalogs are organised.</p>
        <div class="row">
          <input type="url" class="grow" autocomplete="off">
          <button class="btn primary" data-act="saveurl">Save</button>
          <button class="btn" data-act="cancelurl">Cancel</button>
        </div>
      </div>
    </div>`).join('');
}

function showUrlBox(key, editing) {
  const row = document.querySelector(`#addonList [data-key="${CSS.escape(key)}"]`);
  const box = row.querySelector('.newurl');
  const input = box.querySelector('input');
  box.hidden = false;
  input.value = addons.find(a => a.key === key).url;
  input.select();
  box.querySelector('[data-act=saveurl]').hidden = !editing;
  box.querySelector('p').hidden = !editing;
  if (editing) input.focus();
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (e) {
    return false;  // http pages often can't use the clipboard API
  }
}
