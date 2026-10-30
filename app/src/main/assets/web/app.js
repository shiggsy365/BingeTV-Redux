// Account settings, then starting the page: loaded last, as it runs straight away and uses
// every other script's functions.

// ---- Settings ----
let editingSource = null;
function renderSources() {
  const extra = (state.sources || []).filter(x => !x.main);
  $('sourceList').innerHTML = extra.length ? extra.map(x => `
    <div class="row" style="padding:8px 0;border-top:1px solid var(--line)" data-index="${x.index}">
      <div class="grow"><div class="cname">${esc(x.name)}</div>
        <div class="muted">${esc(x.server)} · ${esc(x.username)}${x.useHls ? ' · HLS' : ''}</div></div>
      <button class="btn" data-src="edit">Edit</button>
      <button class="btn danger" data-src="delete">Remove</button>
    </div>`).join('') : '<p class="muted">Only the main account so far.</p>';
  fillSourceSelect($('catSource'));
}
function resetSourceForm() {
  editingSource = null;
  ['srcName', 'srcServer', 'srcUser', 'srcPass'].forEach(id => $(id).value = '');
  $('srcHls').checked = false; $('srcPass').placeholder = '';
  $('sourceFormTitle').textContent = 'Add a source';
  $('srcSave').textContent = 'Add source'; $('srcCancel').hidden = true;
}
$('sourceList').onclick = e => {
  const row = e.target.closest('[data-index]'), act = e.target.dataset.src;
  if (!row || !act) return;
  const x = state.sources.find(s => s.index === Number(row.dataset.index));
  if (act === 'edit') {
    editingSource = x.index;
    $('srcName').value = x.name; $('srcServer').value = x.server; $('srcUser').value = x.username;
    $('srcPass').value = ''; $('srcPass').placeholder = 'Unchanged'; $('srcHls').checked = x.useHls;
    $('sourceFormTitle').textContent = 'Edit ' + x.name;
    $('srcSave').textContent = 'Save changes'; $('srcCancel').hidden = false;
  } else if (act === 'delete' && confirm(`Remove ${x.name}? Its groups and guide links are removed too.`)) {
    run(async () => { categories = {}; applyState(await api('/api/sources/delete', {index: x.index})); toast('Source removed'); });
  }
};
$('srcCancel').onclick = resetSourceForm;
$('srcSave').onclick = () => run(async () => {
  const body = {name: $('srcName').value, server: $('srcServer').value, username: $('srcUser').value,
                password: $('srcPass').value, useHls: $('srcHls').checked};
  const btn = $('srcSave'); btn.disabled = true; const label = btn.textContent; btn.textContent = 'Checking login…';
  try {
    if (editingSource == null) applyState(await api('/api/sources', body));
    else applyState(await api('/api/sources/update', Object.assign({index: editingSource}, body)));
    toast(editingSource == null ? 'Source added' : 'Source saved');
    categories = {}; resetSourceForm();
  } finally { btn.disabled = false; if (btn.textContent === 'Checking login…') btn.textContent = label; }
});

function renderSettings() {
  const s = state.settings;
  if (document.activeElement && document.activeElement.closest('#tab-accounts')) return;
  $('setName').value = s.name; $('setServer').value = s.server; $('setUser').value = s.username; $('setPass').value = '';
  $('setPass').placeholder = s.hasPassword ? 'Unchanged' : 'Password';
  $('setHls').checked = s.useHls; $('setPrefixes').value = s.prefixes; $('setSuffixes').value = s.suffixes;
}
$('saveSettings').onclick = () => run(async () => {
  const s = await api('/api/settings', {
    name: $('setName').value, server: $('setServer').value, username: $('setUser').value, password: $('setPass').value,
    useHls: $('setHls').checked, prefixes: $('setPrefixes').value, suffixes: $('setSuffixes').value});
  categories = {}; applyState(s);
  toast(s.accountError ? 'Saved, but: ' + s.accountError : 'Saved. Sync to update the TV.');
  if (selectedGroup) selectGroup(selectedGroup);
});

$('downloadBackup').onclick = () => {
  // A plain navigation lets the browser save the zip under the server's filename.
  window.location.href = '/api/backup?password=' + ($('backupPassword').checked ? '1' : '0');
};
$('restoreBackup').onclick = () => run(async () => {
  const file = $('restoreFile').files[0];
  if (!file) { toast('Choose a backup file first'); return; }
  if (!confirm('Restoring replaces what\'s set up on this device (addons, profiles, accounts, channels, guides and logos), then syncs. Continue?')) return;
  const btn = $('restoreBackup'); btn.disabled = true; btn.textContent = 'Restoring…';
  try {
    const res = await fetch('/api/restore', {method: 'POST',
        headers: {'Content-Type': 'application/zip'}, body: file});
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || res.statusText);
    categories = {}; selectedGroup = null;
    $('channelPanel').innerHTML = '<p class="muted">Pick a group to see its channels.</p>';
    applyState(data);
    const r = data.restored;
    toast(`Restored ${r.logos} logo(s) and ${r.guides} guide list(s)` +
        (r.passwordRestored ? '' : '; the backup had no password, so the current one was kept') +
        '. Syncing now.');
    $('restoreFile').value = '';
  } finally { btn.disabled = false; btn.textContent = 'Restore'; }
});
$('syncBtn').onclick = () => run(async () => {
  await api('/api/sync', {}); toast('Sync started');
  state.sync.running = true; state.sync.status = 'Syncing…'; renderSync();
});
setInterval(() => { if ($('pair').hidden && state) run(async () => { const s = await api('/api/state'); state.sync = s.sync; renderSync(); }); }, 4000);

function start() {
  initAddons();
  initCatalogs();
  initProfiles();
  run(async () => applyState(await api('/api/state')));
  run(async () => {
    await loadAddonProfiles();
    let tab = 'addons';
    try { tab = localStorage.getItem('tab') || 'addons'; } catch (e) { /* not kept */ }
    openTab(TABS.includes(tab) ? tab : 'addons');
  });
}
// The TV's QR code opens the page with the PIN in the address: pair with it, then drop it
// from the address so it isn't kept in the browser's history.
(async () => {
  const pin = new URLSearchParams(location.search).get('pin');
  if (pin) {
    history.replaceState(null, '', location.pathname);
    try { await sendPin(pin); } catch (e) { showPair(); $('pairError').textContent = e.message; return; }
  }
  start();
})();
