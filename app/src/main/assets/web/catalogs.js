// Catalogs tab: hubs, the catalogs in each, what's on Home, and Search. Loaded before the
// page's main script, whose helpers ($, api, run, toast, esc) these functions use.

let catalogData = null;
let currentHub = 'movies';
let catalogsReady = false;

const TYPE_LABELS = {movie: 'Movies', series: 'Series', tv: 'TV', channel: 'Channels', anime: 'Anime'};

function initCatalogs() {
  if (catalogsReady) return;
  catalogsReady = true;
  $('hubChips').onclick = e => {
    const chip = e.target.closest('[data-hub]');
    if (chip) { currentHub = chip.dataset.hub; renderCatalogs(); }
  };
  $('hubAdd').onclick = () => run(async () => {
    const name = $('hubNew').value.trim();
    if (!name) { toast('Name the hub first'); return; }
    const data = await api('/api/hubs', {name});
    currentHub = data.hubs[data.hubs.length - 1].id;
    $('hubNew').value = '';
    showCatalogs(data);
    toast('Added ' + name + '. Move catalogs into it with their Hub menus.');
  });
  $('hubIcons').onclick = e => {
    const chip = e.target.closest('[data-icon]');
    if (!chip) return;
    run(async () => {
      showCatalogs(await api('/api/hubs/update', {id: currentHub, icon: chip.dataset.icon}));
      toast('Icon changed');
    });
  };
  $('hubRename').onclick = () => run(async () => {
    showCatalogs(await api('/api/hubs/update', {id: currentHub, name: $('hubName').value.trim()}));
    toast('Renamed');
  });
  $('hubDelete').onclick = () => run(async () => {
    const hub = catalogData.hubs.find(h => h.id === currentHub);
    if (!confirm('Remove ' + hub.name + '? Its catalogs go back to their usual hubs.')) return;
    currentHub = 'movies';
    showCatalogs(await api('/api/hubs/delete', {id: hub.id}));
  });
  for (const id of ['hubCatalogs', 'searchCatalogs']) {
    $(id).onclick = e => {
      const btn = e.target.closest('button[data-move]');
      if (!btn) return;
      const key = btn.closest('[data-key]').dataset.key;
      const field = id === 'searchCatalogs' ? 'moveSearch' : 'move';
      run(async () => showCatalogs(await api('/api/catalogs/update', {key, [field]: Number(btn.dataset.move)})));
    };
    $(id).onchange = e => {
      const el = e.target;
      const field = el.dataset.field;
      if (!field) return;
      const key = el.closest('[data-key]').dataset.key;
      const value = el.type === 'checkbox' ? el.checked : el.value.trim();
      run(async () => {
        showCatalogs(await api('/api/catalogs/update', {key, [field]: value}));
        if (field === 'hub') toast('Moved');
      });
    };
  }
}

async function loadCatalogs() {
  showCatalogs(await api('/api/catalogs'));
}

function showCatalogs(data) {
  catalogData = data;
  if (!data.hubs.some(h => h.id === currentHub)) currentHub = 'movies';
  renderCatalogs();
  renderSearchCatalogs();
}

function renderCatalogs() {
  const hubs = catalogData.hubs;
  $('hubChips').innerHTML = hubs.map(h =>
      `<button class="chip${h.id === currentHub ? ' active' : ''}" data-hub="${esc(h.id)}">${esc(h.name)}
        <span class="muted">${h.catalogs.filter(c => c.shown).length}</span></button>`).join('');
  const hub = hubs.find(h => h.id === currentHub);
  $('hubName').value = hub.name;
  // The same shapes the TV draws.
  $('hubIcons').innerHTML = (catalogData.icons || []).map(i =>
      `<button class="chip icon-chip${i.key === hub.icon ? ' active' : ''}" data-icon="${esc(i.key)}"
        title="${esc(i.label)}"><svg viewBox="0 0 24 24"><path d="${esc(i.path)}"/></svg></button>`).join('');
  $('hubDelete').hidden = !hub.custom;
  const options = hubs.map(h => `<option value="${esc(h.id)}">${esc(h.name)}</option>`).join('');
  if (!hub.catalogs.length) {
    $('hubCatalogs').innerHTML = '<p class="muted">No catalogs here. Move one in with its Hub menu in another hub.</p>';
    return;
  }
  $('hubCatalogs').innerHTML = hub.catalogs.map((c, i) => `
    <div class="catrow${c.shown ? '' : ' off'}" data-key="${esc(c.key)}">
      <label class="row" title="Show this catalog in the hub"><input type="checkbox" data-field="shown"${c.shown ? ' checked' : ''}></label>
      <div class="name"><input type="text" data-field="name" value="${esc(c.customName)}" placeholder="${esc(c.displayName)}"></div>
      <div class="src muted ellipsis">${esc(c.addon)} · ${esc(TYPE_LABELS[c.type] || c.type)}${c.customName ? ' · was “' + esc(c.name) + '”' : ''}</div>
      <select data-field="hub" title="Hub">${options.replace(`value="${esc(hub.id)}"`, `value="${esc(hub.id)}" selected`)}</select>
      <button class="btn" data-move="-1"${i === 0 ? ' disabled' : ''} title="Move up">▲</button>
      <button class="btn" data-move="1"${i === hub.catalogs.length - 1 ? ' disabled' : ''} title="Move down">▼</button>
    </div>`).join('');
}

function renderSearchCatalogs() {
  const list = catalogData.search;
  if (!list.length) {
    $('searchCatalogs').innerHTML = '<p class="muted">None of your addons can search.</p>';
    return;
  }
  $('searchCatalogs').innerHTML = list.map((c, i) => `
    <div class="catrow${c.enabled ? '' : ' off'}" data-key="${esc(c.key)}">
      <label class="row" title="Use in Search"><input type="checkbox" data-field="search"${c.enabled ? ' checked' : ''}></label>
      <div class="name"><input type="text" data-field="searchName" value="${c.searchName === c.name ? '' : esc(c.searchName)}" placeholder="${esc(c.name)}"></div>
      <div class="src muted ellipsis">${esc(c.addon)} · ${c.people ? 'People' : esc(TYPE_LABELS[c.type] || c.type)}</div>
      <button class="btn" data-move="-1"${i === 0 ? ' disabled' : ''} title="Move up">▲</button>
      <button class="btn" data-move="1"${i === list.length - 1 ? ' disabled' : ''} title="Move down">▼</button>
    </div>`).join('');
}
