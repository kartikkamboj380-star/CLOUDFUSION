import SparkMD5 from 'spark-md5';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Activity, ArrowDown, ArrowDownToLine, ArrowRight, ArrowUp, ArrowUpFromLine, Check, Cloud,
  Copy, Database, ExternalLink, File, FileImage, FileText, Folder, FolderPlus, HardDrive, Home,
  Link, LoaderCircle, LockKeyhole, LogOut, MoreHorizontal, Plus, RefreshCw, Search, Settings,
  ShieldCheck, Trash2, TriangleAlert, Users, X,
} from 'lucide-react';
import { type Account, type Dashboard, type DriveFile, type Page, type Sharing, type Transfer, ApiError, formatBytes, googleAuthorizationUrl, googleDriveLink, request } from './api';

type View = 'Home' | 'Files' | 'Transfers' | 'Accounts' | 'Settings';
type Dialog =
  | { kind: 'rename' | 'move' | 'copy' | 'sharing'; file: DriveFile }
  | { kind: 'folder' }
  | { kind: 'upload'; file: File; transfer: Transfer };

type FileWriter = { write(data: Uint8Array): Promise<void>; close(): Promise<void>; abort(): Promise<void> };
type SavePicker = (options: { suggestedName: string }) => Promise<{ createWritable(): Promise<FileWriter> }>;

const nav: { label: View; icon: typeof Home }[] = [
  { label: 'Home', icon: Home }, { label: 'Files', icon: Folder }, { label: 'Transfers', icon: Activity },
  { label: 'Accounts', icon: Users }, { label: 'Settings', icon: Settings },
];
const active = ['initializing', 'pending', 'in_progress', 'cancel_requested'];
const statusLabel = (s: string) => s.replace(/_/g, ' ');
const formatDate = (s: string | null) => s ? new Date(s).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : '—';
const fileIcon = (file: DriveFile) => file.mime_type.includes('folder') ? Folder : file.mime_type.startsWith('image/') ? FileImage : file.mime_type.includes('document') || file.mime_type.startsWith('text/') || file.mime_type === 'application/pdf' ? FileText : File;

function Brand({ compact = false }: { compact?: boolean }) {
  return <div className="brand"><span className="brand-mark"><Cloud size={21} strokeWidth={2.1} /></span>{!compact && <span>CLOUD<span>FUSION</span></span>}</div>;
}

function Modal({ title, close, children }: { title: string; close: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey);
  }, [close]);
  return <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}>
    <section className="modal" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
      <div className="modal-title"><h2 id="dialog-title">{title}</h2><button className="icon-button" onClick={close} aria-label="Close dialog"><X size={19}/></button></div>
      {children}
    </section>
  </div>;
}

function Auth({ onAuth, error, busy }: { onAuth: (email: string, password: string, register: boolean) => void; error: string | null; busy: boolean }) {
  const [register, setRegister] = useState(false); const [email, setEmail] = useState(''); const [password, setPassword] = useState('');
  return <main className="auth-layout"><div className="auth-aside"><Brand/><div className="auth-copy"><span className="eyebrow"><span className="eyebrow-dot"/> A LITTLE MORE ROOM TO BREATHE</span><h1>Your cloud,<br/><em>beautifully</em><br/>in one place.</h1><p>Bring your Google Drive accounts into one calm, organized space. Your files stay right where they belong.</p><div className="auth-note"><ShieldCheck size={18}/> Your accounts remain separate and in your control.</div></div><span className="aside-footer">CLOUDFUSION / YOUR SPACE, SIMPLIFIED</span></div>
    <div className="auth-main"><div className="auth-card"><div className="mobile-brand"><Brand/></div><span className="eyebrow">YOUR WORKSPACE</span><h2>{register ? 'Create your account' : 'Welcome back.'}</h2><p>{register ? 'Start with a CloudFusion login, then connect Google Drive.' : 'Sign in to your CloudFusion workspace.'}</p>
      {error && <div className="banner error"><TriangleAlert size={16}/>{error}</div>}
      <form onSubmit={e => { e.preventDefault(); onAuth(email, password, register); }}>
        <label>Email address<input type="email" autoComplete="email" required value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" /></label>
        <label>Password<input type="password" autoComplete={register ? 'new-password' : 'current-password'} minLength={register ? 12 : 1} required value={password} onChange={e => setPassword(e.target.value)} placeholder={register ? 'At least 12 characters' : 'Enter your password'} /></label>
        <button className="button primary full" disabled={busy}>{busy ? <LoaderCircle className="spin" size={17}/> : null}{register ? 'Create account' : 'Sign in'} <ArrowRight size={17}/></button>
      </form>
      <div className="auth-switch">{register ? 'Already have an account?' : 'New to CloudFusion?'} <button className="text-button" onClick={() => setRegister(!register)}>{register ? 'Sign in' : 'Create an account'}</button></div>
      <div className="auth-hint"><LockKeyhole size={15}/> A configured CloudFusion backend is required.</div>
    </div></div></main>;
}

export default function App() {
  const [token, setToken] = useState(() => sessionStorage.getItem('cloudfusion_token') || '');
  const [email, setEmail] = useState(''); const [view, setView] = useState<View>('Home');
  const [dashboard, setDashboard] = useState<Dashboard | null>(null); const [accounts, setAccounts] = useState<Account[]>([]);
  const [files, setFiles] = useState<DriveFile[]>([]); const [nextOffset, setNextOffset] = useState<number | null>(null);
  const [transfers, setTransfers] = useState<Transfer[]>([]); const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null); const [notice, setNotice] = useState<string | null>(null);
  const [search, setSearch] = useState(''); const [sort, setSort] = useState('modified'); const [direction, setDirection] = useState('desc'); const [fileType, setFileType] = useState('all');
  const [uploadAccount, setUploadAccount] = useState(''); const [dialog, setDialog] = useState<Dialog | null>(null);
  const [sharing, setSharing] = useState<Sharing | null>(null); const [formValue, setFormValue] = useState('');
  const [folderAccount, setFolderAccount] = useState(''); const [parentId, setParentId] = useState(''); const [copyDestination, setCopyDestination] = useState('');
  const [progress, setProgress] = useState<string | null>(null); const [resumeTarget, setResumeTarget] = useState<Transfer | null>(null);
  const fileInput = useRef<HTMLInputElement>(null); const resumeInput = useRef<HTMLInputElement>(null);
  const uploadController = useRef<{ id: string; controller: AbortController } | null>(null);
  const downloadController = useRef<{ id: string; controller: AbortController } | null>(null);

  const fail = (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      sessionStorage.removeItem('cloudfusion_token'); setToken(''); setEmail('');
      setError('Your CloudFusion session expired. Sign in again.');
    } else if (e instanceof Error && e.name !== 'AbortError') setError(e.message);
  };
  const perform = async (fn: () => Promise<void>) => { setBusy(true); setError(null); try { await fn(); } catch (e) { fail(e); } finally { setBusy(false); } };
  const loadOverview = async () => {
    const [me, acc, dash] = await Promise.all([
      request<{ email: string }>(token, '/api/auth/me'), request<Account[]>(token, '/api/accounts'), request<Dashboard>(token, '/api/dashboard'),
    ]);
    setEmail(me.email); setAccounts(acc); setDashboard(dash);
  };
  const loadFiles = async (offset = 0, override?: { search?: string; sort?: string; direction?: string; type?: string }) => {
    const params = new URLSearchParams({ search: override?.search ?? search, sort: override?.sort ?? sort, direction: override?.direction ?? direction, type: override?.type ?? fileType, limit: '30', offset: String(offset) });
    const page = await request<Page<DriveFile>>(token, `/api/files?${params}`);
    setFiles(current => offset ? [...current, ...page.items] : page.items); setNextOffset(page.next_offset);
  };
  const loadTransfers = async () => { const page = await request<Page<Transfer>>(token, '/api/transfers?limit=100'); setTransfers(page.items); };
  useEffect(() => { if (token) { void perform(async () => { await Promise.all([loadOverview(), loadFiles(), loadTransfers()]); }); } }, [token]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!token || view !== 'Transfers') return;
    const timer = window.setInterval(() => { void loadTransfers().catch(fail); }, 8000);
    return () => window.clearInterval(timer);
  }, [token, view]); // eslint-disable-line react-hooks/exhaustive-deps

  const authenticate = (userEmail: string, password: string, register: boolean) => void perform(async () => {
    const data = await request<{ token: string; email: string }>('', `/api/auth/${register ? 'register' : 'login'}`, { method: 'POST', json: { email: userEmail, password } });
    sessionStorage.setItem('cloudfusion_token', data.token); setToken(data.token); setEmail(data.email);
  });
  const logout = () => void perform(async () => {
    try { await request(token, '/api/auth/logout', { method: 'POST' }); } finally {
      uploadController.current?.controller.abort(); downloadController.current?.controller.abort();
      sessionStorage.removeItem('cloudfusion_token'); setToken(''); setDashboard(null); setFiles([]); setAccounts([]); setTransfers([]); setEmail('');
    }
  });
  const connect = () => {
    // Open synchronously in the click handler so popup blockers don't swallow the OAuth window.
    const popup = window.open('about:blank', '_blank');
    if (!popup) { setError('Allow pop-ups to connect a Google account.'); return; }
    popup.opener = null;
    void perform(async () => {
      try {
        const data = await request<{ authorization_url: string }>(token, '/api/oauth/start', { method: 'POST' });
        if (!googleAuthorizationUrl(data.authorization_url)) throw new Error('Unexpected Google authorization URL');
        popup.location.replace(data.authorization_url);
        setNotice('Complete Google authorization in the new tab, then refresh accounts.');
      } catch (e) { popup.close(); throw e; }
    });
  };
  const refreshAccounts = () => void perform(async () => {
    const refreshed = await request<Account[]>(token, '/api/accounts/refresh', { method: 'POST' }); await loadOverview();
    setNotice(refreshed.length ? 'Storage quotas refreshed from Google Drive.' : 'Connect a Google account before refreshing quotas.');
  });
  const syncFiles = () => void perform(async () => {
    const result = await request<{ accounts: { error: string | null }[] }>(token, '/api/files/sync', { method: 'POST' });
    await Promise.all([loadFiles(), loadOverview()]);
    const failures = result.accounts.filter(a => a.error).map(a => a.error).join('; ');
    setNotice(!result.accounts.length ? 'Connect a Google account before refreshing files.' : failures ? `Some accounts could not refresh: ${failures}` : 'Files refreshed from Google Drive.');
  });
  const disconnect = (account: Account) => {
    if (!window.confirm(`Disconnect ${account.email}? Your files will remain in Google Drive.`)) return;
    void perform(async () => { await request(token, `/api/accounts/${account.id}`, { method: 'DELETE' }); await Promise.all([loadOverview(), loadFiles()]); setNotice('Account disconnected.'); });
  };
  const openDialog = (d: Dialog) => {
    setDialog(d); setFormValue(d.kind === 'rename' ? d.file.name : ''); setParentId(''); setFolderAccount(accounts[0]?.id || ''); setCopyDestination(d.kind === 'copy' ? accounts.find(a => a.id !== d.file.account_id)?.id || '' : ''); setSharing(null);
    if (d.kind === 'sharing') void perform(async () => setSharing(await request<Sharing>(token, `/api/files/${d.file.account_id}/${d.file.id}/permissions`)));
  };
  const mutate = (file: DriveFile, method: string, suffix: string, body?: object) => perform(async () => {
    await request(token, `/api/files/${file.account_id}/${file.id}${suffix}`, { method, json: body });
    setDialog(null); await loadFiles(); setNotice('File updated in Google Drive.');
  });
  const trash = (file: DriveFile) => { if (window.confirm(`Move “${file.name}” to Google Drive trash?`)) void mutate(file, 'DELETE', ''); };
  const makePublic = (file: DriveFile) => {
    if (!window.confirm('Make this file public? Anyone with the link may be able to view it.')) return;
    void perform(async () => { setSharing(await request<Sharing>(token, `/api/files/${file.account_id}/${file.id}/public`, { method: 'POST', json: { confirm_public: true } })); setNotice('Public link enabled.'); });
  };
  const revokePublic = (file: DriveFile) => void perform(async () => {
    setSharing(await request<Sharing>(token, `/api/files/${file.account_id}/${file.id}/public`, { method: 'DELETE' })); setNotice('Public access revoked.');
  });
  const prepareUpload = (file: File) => void perform(async () => {
    if (!file.size) throw new Error('Please choose a non-empty file.');
    if (uploadController.current) throw new Error('An upload is already running in this browser tab.');
    const transfer = await request<Transfer>(token, '/api/uploads', { method: 'POST', json: { name: file.name, size: file.size, mime_type: file.type || 'application/octet-stream', account_id: uploadAccount || null, idempotency_key: crypto.randomUUID() } });
    openDialog({ kind: 'upload', file, transfer });
  });
  const upload = async (file: File, existing: Transfer) => {
    if (uploadController.current) { setError('An upload is already running in this browser tab.'); return; }
    setDialog(null);
    const controller = new AbortController(); uploadController.current = { id: existing.id, controller };
    try {
      let transfer = await request<Transfer>(token, `/api/transfers/${existing.id}`);
      if (transfer.status === 'failed') transfer = await request<Transfer>(token, `/api/transfers/${existing.id}/retry`, { method: 'POST' });
      if (file.name !== transfer.name || file.size !== transfer.size) throw new Error('Select the original file with the same name and size.');
      let offset = transfer.bytes_transferred;
      const md5 = new SparkMD5.ArrayBuffer();
      // Hash the prefix again on resume, without retaining the whole file in memory.
      for (let pos = 0; pos < offset; pos += 4 * 1024 * 1024) {
        if (controller.signal.aborted) throw new DOMException('Cancelled', 'AbortError');
        md5.append(await file.slice(pos, Math.min(offset, pos + 4 * 1024 * 1024)).arrayBuffer());
      }
      while (offset < file.size) {
        const end = Math.min(file.size, offset + 4 * 1024 * 1024);
        const chunk = await file.slice(offset, end).arrayBuffer();
        md5.append(chunk);
        transfer = await request<Transfer>(token, `/api/uploads/${transfer.id}/chunk`, { method: 'PUT', bytes: chunk, offset, signal: controller.signal });
        if (transfer.bytes_transferred !== end) throw new Error('Upload offset changed. Select the original file again to resume.');
        offset = end; setProgress(`Uploading ${file.name} · ${formatBytes(offset)} / ${formatBytes(file.size)}`);
      }
      const localMd5 = md5.end();
      if (transfer.result_md5 && transfer.result_md5.toLowerCase() !== localMd5.toLowerCase()) throw new Error('Checksum mismatch. Review the destination file in Google Drive before retrying.');
      setNotice(transfer.result_md5 ? 'Upload complete. MD5 checksum verified. Refresh files to see it.' : 'Upload complete. Google did not provide a checksum. Refresh files to see it.');
      await Promise.all([loadOverview(), loadTransfers()]);
    } catch (e) { fail(e); } finally { uploadController.current = null; setProgress(null); }
  };
  const cancelTransfer = (t: Transfer) => void perform(async () => {
    if (uploadController.current?.id === t.id) uploadController.current.controller.abort();
    if (downloadController.current?.id === t.id) downloadController.current.controller.abort();
    await request(token, `/api/transfers/${t.id}/cancel`, { method: 'POST' }); await loadTransfers(); setNotice('Transfer cancelled.');
  });
  const download = async (file: DriveFile) => {
    const save = (window as Window & { showSaveFilePicker?: SavePicker }).showSaveFilePicker;
    if (!save) { setError('Streaming downloads require a browser with File System Access. Use the Android app or a supported desktop browser.'); return; }
    let writer: FileWriter | null = null;
    try {
      const handle = await save({ suggestedName: file.name }); writer = await handle.createWritable();
      const controller = new AbortController();
      const response = await fetch(`/api/files/${file.account_id}/${file.id}/download`, { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
      if (!response.ok || !response.body) throw new Error('Could not start download. Google Workspace files and folders are not supported.');
      const id = response.headers.get('X-Transfer-Id'); if (id) downloadController.current = { id, controller };
      const reader = response.body.getReader(); let total = 0;
      while (true) { const { done, value } = await reader.read(); if (done) break; await writer.write(value); total += value.byteLength; setProgress(`Downloading ${file.name} · ${formatBytes(total)}`); }
      if (file.size != null && total !== file.size) throw new Error('Download ended before the expected file size.');
      await writer.close(); writer = null; setNotice('Download complete.'); await loadTransfers();
    } catch (e) { if (writer) await writer.abort().catch(() => undefined); fail(e); }
    finally { downloadController.current = null; setProgress(null); }
  };

  if (!token) return <Auth onAuth={authenticate} error={error} busy={busy}/>;
  const title: Record<View, string> = { Home: 'Your space, unified.', Files: 'Your files', Transfers: 'Transfers', Accounts: 'Connected accounts', Settings: 'Settings' };
  const subtitle: Record<View, string> = { Home: 'One view for everything you keep in the cloud.', Files: 'Everything in its place, across your connected Drives.', Transfers: 'Keep an eye on the things in motion.', Accounts: 'Separate accounts, a clearer view.', Settings: 'Manage your CloudFusion workspace.' };
  return <div className="app-shell">
    <aside className="sidebar"><Brand/><div className="sidebar-label">WORKSPACE</div><nav>{nav.map(({ label, icon: Icon }) => <button key={label} className={`nav-item ${view === label ? 'selected' : ''}`} onClick={() => setView(label)}><Icon size={19}/><span>{label}</span>{view === label && <span className="nav-dot"/>}</button>)}</nav><div className="sidebar-bottom"><div className="privacy-card"><ShieldCheck size={20}/><strong>Your space, your rules.</strong><p>Drive accounts are never merged. You remain in control.</p></div><div className="sidebar-user"><span className="avatar">{email.slice(0, 1).toUpperCase() || 'C'}</span><span><strong>CloudFusion</strong><small>{email}</small></span><button className="icon-button" aria-label="Sign out" onClick={logout}><LogOut size={17}/></button></div></div></aside>
    <div className="main-area"><header className="topbar"><div className="mobile-brand"><Brand compact/></div><div className="breadcrumb">WORKSPACE <span>/</span> <strong>{view.toUpperCase()}</strong></div><div className="topbar-right"><span className="status-dot"/> <span className="topbar-status">PRIVATE WORKSPACE</span><span className="avatar small">{email.slice(0, 1).toUpperCase() || 'C'}</span></div></header>
      <main className="content"><div className="page-heading"><div><span className="eyebrow">{view === 'Home' ? 'YOUR CLOUD AT A GLANCE' : 'CLOUDFUSION / ' + view.toUpperCase()}</span><h1>{title[view]}</h1><p>{subtitle[view]}</p></div><div className="heading-actions">{view === 'Home' && <button className="button secondary" onClick={refreshAccounts} disabled={busy}><RefreshCw size={16}/> Refresh storage</button>}{view === 'Files' && <button className="button secondary" onClick={syncFiles} disabled={busy}><RefreshCw size={16}/> Refresh files</button>}{view === 'Accounts' && <button className="button primary" onClick={connect} disabled={busy}><Plus size={17}/> Connect account</button>}</div></div>
        {error && <div className="banner error" role="alert"><TriangleAlert size={17}/><span>{error}</span><button className="icon-button" onClick={() => setError(null)} aria-label="Dismiss"><X size={16}/></button></div>}
        {notice && <div className="banner success"><Check size={17}/><span>{notice}</span><button className="icon-button" onClick={() => setNotice(null)} aria-label="Dismiss"><X size={16}/></button></div>}
        {progress && <div className="banner info"><LoaderCircle size={17} className="spin"/>{progress}</div>}
        {busy && <div className="loading-bar"/>}
        {view === 'Home' && <HomeView dashboard={dashboard} accounts={accounts} go={setView} connect={connect}/>}
        {view === 'Accounts' && <AccountsView accounts={accounts} connect={connect} refresh={refreshAccounts} disconnect={disconnect}/>}
        {view === 'Files' && <FilesView files={files} accounts={accounts} search={search} setSearch={setSearch} sort={sort} setSort={setSort} direction={direction} setDirection={setDirection} fileType={fileType} setFileType={setFileType} load={async (offset, override) => { await perform(() => loadFiles(offset, override)); }} nextOffset={nextOffset} uploadAccount={uploadAccount} setUploadAccount={setUploadAccount} upload={() => fileInput.current?.click()} folder={() => openDialog({ kind: 'folder' })} action={(kind, file) => openDialog({ kind, file })} trash={trash} download={download} sync={syncFiles}/>}
        {view === 'Transfers' && <TransfersView transfers={transfers} refresh={() => void perform(loadTransfers)} cancel={cancelTransfer} retry={t => { if (t.kind === 'upload') { setResumeTarget(t); resumeInput.current?.click(); } else void perform(async () => { await request(token, `/api/transfers/${t.id}/retry`, { method: 'POST' }); await loadTransfers(); setNotice('Copy resumed on the backend.'); }); }}/>} 
        {view === 'Settings' && <SettingsView email={email} logout={logout}/>}
      </main></div>
    <nav className="mobile-nav">{nav.map(({ label, icon: Icon }) => <button key={label} className={view === label ? 'selected' : ''} onClick={() => setView(label)}><Icon size={20}/><span>{label}</span></button>)}</nav>
    <input ref={fileInput} type="file" hidden onChange={e => { const f = e.target.files?.[0]; if (f) prepareUpload(f); e.target.value = ''; }}/>
    <input ref={resumeInput} type="file" hidden onChange={e => { const f = e.target.files?.[0]; if (f && resumeTarget) void upload(f, resumeTarget); e.target.value = ''; setResumeTarget(null); }}/>
    {dialog && <Modal title={dialog.kind === 'folder' ? 'Create a folder' : dialog.kind === 'upload' ? 'Ready to upload?' : dialog.kind === 'rename' ? 'Rename file' : dialog.kind === 'move' ? 'Move file' : dialog.kind === 'copy' ? 'Copy to another account' : 'Sharing & permissions'} close={() => {
      if (dialog.kind === 'upload') void request(token, `/api/transfers/${dialog.transfer.id}/cancel`, { method: 'POST' }).catch(fail);
      setDialog(null);
    }}>
      {dialog.kind === 'upload' && <><p className="modal-copy">Your file will remain a normal file in the selected Google Drive account.</p><div className="detail-box"><strong>{dialog.file.name}</strong><span>{formatBytes(dialog.file.size)}</span><span>Destination: {accounts.find(a => a.id === dialog.transfer.destination_account_id)?.email || 'Selected account'}</span></div><div className="modal-actions"><button className="button secondary" onClick={() => { void request(token, `/api/transfers/${dialog.transfer.id}/cancel`, { method: 'POST' }).catch(fail); setDialog(null); }}>Cancel</button><button className="button primary" onClick={() => void upload(dialog.file, dialog.transfer)}>Start upload <ArrowRight size={16}/></button></div></>}
      {dialog.kind === 'folder' && <form onSubmit={e => { e.preventDefault(); void perform(async () => { await request(token, '/api/files/folders', { method: 'POST', json: { account_id: folderAccount, name: formValue, parent_id: parentId || null } }); setDialog(null); await loadFiles(); setNotice('Folder created.'); }); }}><label>Account<select value={folderAccount} onChange={e => setFolderAccount(e.target.value)} required>{accounts.map(a => <option key={a.id} value={a.id}>{a.email}</option>)}</select></label><label>Folder name<input value={formValue} onChange={e => setFormValue(e.target.value)} required/></label><label>Parent folder ID <span className="optional">optional</span><input value={parentId} onChange={e => setParentId(e.target.value)} placeholder="Leave blank for My Drive"/></label><div className="modal-actions"><button type="button" className="button secondary" onClick={() => setDialog(null)}>Cancel</button><button className="button primary" disabled={!folderAccount}>Create folder</button></div></form>}
      {(dialog.kind === 'rename' || dialog.kind === 'move') && <form onSubmit={e => { e.preventDefault(); void mutate(dialog.file, dialog.kind === 'rename' ? 'PATCH' : 'POST', dialog.kind === 'rename' ? '' : '/move', dialog.kind === 'rename' ? { name: formValue } : { parent_id: formValue }); }}><p className="modal-copy">{dialog.file.name} · {dialog.file.account_email}</p><label>{dialog.kind === 'rename' ? 'New name' : 'Destination folder ID (or root)'}<input value={formValue} onChange={e => setFormValue(e.target.value)} required autoFocus/></label><div className="modal-actions"><button type="button" className="button secondary" onClick={() => setDialog(null)}>Cancel</button><button className="button primary">Save changes</button></div></form>}
      {dialog.kind === 'copy' && <form onSubmit={e => { e.preventDefault(); void perform(async () => { await request(token, '/api/transfers/copy', { method: 'POST', json: { source_account_id: dialog.file.account_id, source_file_id: dialog.file.id, destination_account_id: copyDestination, idempotency_key: crypto.randomUUID() } }); setDialog(null); await loadTransfers(); setNotice('Copy started on your backend.'); }); }}><p className="modal-copy">The backend streams this file between accounts; it is not downloaded to this device.</p><label>Destination account<select value={copyDestination} onChange={e => setCopyDestination(e.target.value)} required>{accounts.filter(a => a.id !== dialog.file.account_id).map(a => <option key={a.id} value={a.id}>{a.email}</option>)}</select></label><div className="modal-actions"><button type="button" className="button secondary" onClick={() => setDialog(null)}>Cancel</button><button className="button primary" disabled={!copyDestination}>Start copy</button></div></form>}
      {dialog.kind === 'sharing' && <><p className="modal-copy">{dialog.file.name}<br/>Account: {dialog.file.account_email}<br/>Owner: {dialog.file.owner_email || 'Not available'}</p>{sharing ? <><div className="permission-list">{sharing.permissions.length ? sharing.permissions.map(p => <div key={p.id}><span>{p.emailAddress || p.type}</span><strong>{p.role}</strong></div>) : <span>No permissions returned by Google.</span>}</div><p className="muted small-text">A link only works for people with permission unless public access is enabled.</p><div className="modal-actions wrap">{googleDriveLink(sharing.file.webViewLink || dialog.file.web_view_link) && <button className="button secondary" onClick={() => { void navigator.clipboard.writeText(googleDriveLink(sharing.file.webViewLink || dialog.file.web_view_link)!).then(() => setNotice('Link copied.')).catch(fail); }}><Link size={15}/> Copy link</button>}{sharing.permissions.some(p => p.type === 'anyone') ? <button className="button secondary" onClick={() => revokePublic(dialog.file)}>Revoke public</button> : <button className="button primary" onClick={() => makePublic(dialog.file)}>Make public</button>}</div></> : <p className="muted">Loading permissions…</p>}</>}
    </Modal>}
  </div>;
}

function HomeView({ dashboard: d, accounts, go, connect }: { dashboard: Dashboard | null; accounts: Account[]; go: (v: View) => void; connect: () => void }) {
  const utilization = d?.utilization_percent;
  return <div className="home-grid"><div className="home-main">
    <section className="storage-hero"><div className="hero-top"><span className="hero-icon"><Database size={19}/></span><span className="eyebrow">COMBINED STORAGE</span><span className="live-pill"><span/> DRIVE QUOTAS</span></div><div className="hero-body"><div><h2>{formatBytes(d?.used_bytes)}</h2><p>{d?.capacity_bytes != null ? `of ${formatBytes(d.capacity_bytes)} used` : 'Capacity unavailable or unlimited'}</p></div><div className="hero-glyph"><Cloud size={88} strokeWidth={0.65}/></div></div><div className="progress-track hero-progress"><span style={{ width: `${Math.max(0, Math.min(utilization ?? 0, 100))}%` }}/></div><div className="progress-caption"><span>{utilization != null ? `${utilization.toFixed(1)}% used` : 'Usage unavailable'}</span><span>{formatBytes(d?.remaining_bytes)} free</span></div><div className="hero-foot"><ShieldCheck size={14}/> A virtual view — your Google accounts stay separate.</div></section>
    <div className="stats-grid"><Stat label="CONNECTED ACCOUNTS" value={d?.connected_accounts.toString() ?? '—'} caption="Individual Drives" icon={<Users size={18}/>} onClick={() => go('Accounts')}/><Stat label="ACTIVE TRANSFERS" value={d?.active_transfers.toString() ?? '—'} caption="In progress" icon={<Activity size={18}/>} onClick={() => go('Transfers')}/><Stat label="FAILED TRANSFERS" value={d?.failed_transfers.toString() ?? '—'} caption="Need attention" icon={<TriangleAlert size={18}/>} onClick={() => go('Transfers')}/></div>
    <section className="panel"><div className="section-heading"><div><span className="eyebrow">PICK UP WHERE YOU LEFT OFF</span><h2>Recent files</h2></div><button className="text-button" onClick={() => go('Files')}>View all <ArrowRight size={15}/></button></div>
      {d?.recent_files.length ? <div className="recent-list">{d.recent_files.slice(0, 5).map(file => <FileLine key={file.account_id + file.id} file={file}/>)}</div> : <Empty icon={<Folder size={22}/>} title="Your files will appear here" description="Connect a Drive and refresh your files to get started." action="Go to files" onClick={() => go('Files')}/>}</section>
  </div><div className="home-side"><section className="panel"><div className="section-heading"><div><span className="eyebrow">YOUR DRIVE ACCOUNTS</span><h2>Connected spaces</h2></div><button className="icon-button" aria-label="Manage accounts" onClick={() => go('Accounts')}><ArrowRight size={18}/></button></div>
      {accounts.length ? <div className="account-stack">{accounts.slice(0, 4).map(a => <AccountCompact key={a.id} account={a}/>)}</div> : <Empty icon={<HardDrive size={21}/>} title="No accounts yet" description="Connect Google Drive to see your actual usage." action="Connect account" onClick={connect}/>}
      {accounts.length > 0 && <button className="button dashed full" onClick={connect}><Plus size={16}/> Connect another account</button>}</section>
    <section className="panel activity-panel"><div className="section-heading"><div><span className="eyebrow">IN THE LOOP</span><h2>Recent activity</h2></div><button className="text-button" onClick={() => go('Transfers')}>See all</button></div>
      {d?.recent_transfers.length ? d.recent_transfers.slice(0, 4).map(t => <div className="activity-line" key={t.id}><span className="activity-icon">{t.kind === 'upload' ? <ArrowUpFromLine size={16}/> : t.kind === 'download' ? <ArrowDownToLine size={16}/> : <Copy size={16}/>}</span><div><strong>{t.name}</strong><small>{t.kind} · {statusLabel(t.status)}</small></div></div>) : <p className="muted small-text">Your recent uploads and transfers will appear here.</p>}</section>
    <div className="tip-card"><span className="tip-icon"><ShieldCheck size={19}/></span><div><strong>Good to know</strong><p>Files stay in their destination account. CloudFusion never combines your Google accounts.</p></div></div>
  </div></div>;
}

function Stat({ label, value, caption, icon, onClick }: { label: string; value: string; caption: string; icon: ReactNode; onClick: () => void }) {
  return <button className="stat-card" onClick={onClick}><div><span className="eyebrow">{label}</span><span className="stat-icon">{icon}</span></div><strong>{value}</strong><small>{caption}</small></button>;
}
function Empty({ icon, title, description, action, onClick }: { icon: ReactNode; title: string; description: string; action?: string; onClick?: () => void }) {
  return <div className="empty"><span className="empty-icon">{icon}</span><strong>{title}</strong><p>{description}</p>{action && <button className="text-button" onClick={onClick}>{action} <ArrowRight size={14}/></button>}</div>;
}
function AccountCompact({ account: a }: { account: Account }) {
  const used = a.used_bytes, limit = a.limit_bytes; const pct = used != null && limit != null && limit > 0 ? Math.min(100, used / limit * 100) : null;
  return <div className="compact-account"><div className="compact-head"><span className="account-avatar">{a.email.slice(0, 1).toUpperCase()}</span><div><strong>{a.email}</strong><small>{a.reauthorization_required ? 'Reconnect required' : !a.quota_available ? 'Quota unavailable' : a.unlimited ? 'Unlimited quota' : `${formatBytes(a.free_bytes)} free`}</small></div></div>{pct != null && <><div className="progress-track"><span style={{ width: `${pct}%` }}/></div><div className="account-usage">{formatBytes(used)} <span>of {formatBytes(limit)}</span></div></>}</div>;
}
function FileLine({ file }: { file: DriveFile }) {
  const Icon = fileIcon(file);
  return <div className="file-line"><span className="file-icon"><Icon size={19}/></span><div className="file-name"><strong>{file.name}</strong><small>{file.account_email}</small></div><span className="file-size">{formatBytes(file.size)}</span><span className="file-date">{formatDate(file.modified_time)}</span></div>;
}

function AccountsView({ accounts, connect, refresh, disconnect }: { accounts: Account[]; connect: () => void; refresh: () => void; disconnect: (a: Account) => void }) {
  return <><div className="sub-actions"><p className="muted">Connect four accounts or more. Quotas are reported by Google, not assumed.</p><button className="button secondary" onClick={refresh}><RefreshCw size={16}/> Refresh quotas</button></div>
    {!accounts.length ? <section className="panel"><Empty icon={<Users size={22}/>} title="Start with one account" description="Connect your Google Drive to see its storage and files." action="Connect Google account" onClick={connect}/></section> : <div className="account-grid">{accounts.map(a => <section className="panel account-card" key={a.id}><div className="account-card-top"><span className="account-avatar large">{a.email.slice(0,1).toUpperCase()}</span><span className={`badge ${a.reauthorization_required || !a.quota_available ? 'warn' : ''}`}>{a.reauthorization_required ? 'Reconnect' : a.quota_available ? 'Connected' : 'Unavailable'}</span></div><h3>{a.email}</h3><p className="muted">Google Drive account</p><div className="account-divider"/><div className="account-numbers"><div><small>USED</small><strong>{formatBytes(a.used_bytes)}</strong></div><div><small>AVAILABLE</small><strong>{a.unlimited ? 'Unlimited' : formatBytes(a.free_bytes)}</strong></div></div>{a.used_bytes != null && a.limit_bytes != null && a.limit_bytes > 0 && <div className="progress-track"><span style={{width: `${Math.min(100, a.used_bytes / a.limit_bytes * 100)}%`}}/></div>}{a.quota_error && <p className="warning-text">{a.quota_error}</p>}<div className="card-actions">{a.reauthorization_required && <button className="text-button" onClick={connect}>Reconnect</button>}<button className="text-button destructive" onClick={() => disconnect(a)}>Disconnect</button></div></section>)}</div>}
    <div className="info-strip"><ShieldCheck size={18}/><span>Disconnecting removes CloudFusion's saved credentials. It does not delete your Drive files.</span></div></>;
}

type FilesProps = {
  files: DriveFile[]; accounts: Account[]; search: string; setSearch: (s: string) => void; sort: string; setSort: (s: string) => void;
  direction: string; setDirection: (s: string) => void; fileType: string; setFileType: (s: string) => void;
  load: (offset?: number, override?: { search?: string; sort?: string; direction?: string; type?: string }) => Promise<void>;
  nextOffset: number | null; uploadAccount: string; setUploadAccount: (s: string) => void; upload: () => void; folder: () => void;
  action: (kind: 'rename' | 'move' | 'copy' | 'sharing', f: DriveFile) => void; trash: (f: DriveFile) => void; download: (f: DriveFile) => void; sync: () => void;
};
function FilesView(p: FilesProps) {
  return <section className="panel files-panel"><div className="files-actions"><form className="search" onSubmit={e => { e.preventDefault(); void p.load(0); }}><Search size={18}/><input aria-label="Search files by name" placeholder="Search your files..." value={p.search} onChange={e => p.setSearch(e.target.value)}/><button aria-label="Search" type="submit"><ArrowRight size={17}/></button></form><div className="button-row"><select aria-label="Upload destination" value={p.uploadAccount} onChange={e => p.setUploadAccount(e.target.value)}><option value="">Auto allocation</option>{p.accounts.map(a => <option value={a.id} key={a.id}>{a.email}</option>)}</select><button className="button secondary" onClick={p.folder} disabled={!p.accounts.length}><FolderPlus size={16}/> New folder</button><button className="button primary" onClick={p.upload} disabled={!p.accounts.length}><ArrowUpFromLine size={16}/> Upload</button></div></div>
    <div className="file-filters"><div className="filter-pills">{['all', 'images', 'documents', 'videos', 'audio', 'folders'].map(t => <button key={t} className={p.fileType === t ? 'active' : ''} onClick={() => { p.setFileType(t); void p.load(0, { type: t }); }}>{t[0].toUpperCase() + t.slice(1)}</button>)}</div><div className="sort-controls"><span>Sort by</span><select value={p.sort} onChange={e => { p.setSort(e.target.value); void p.load(0, { sort: e.target.value }); }} aria-label="Sort files"><option value="modified">Modified</option><option value="name">Name</option><option value="size">Size</option></select><button className="icon-button" aria-label="Change sort direction" onClick={() => { const next = p.direction === 'asc' ? 'desc' : 'asc'; p.setDirection(next); void p.load(0, { direction: next }); }}>{p.direction === 'asc' ? <ArrowUp size={17}/> : <ArrowDown size={17}/>}</button></div></div>
    {p.files.length ? <><div className="table-head"><span>NAME</span><span>SIZE</span><span>MODIFIED</span><span/></div><div>{p.files.map(file => { const Icon = fileIcon(file); return <div className="file-row" key={file.account_id + ':' + file.id}><span className="file-icon"><Icon size={19}/></span><div className="file-name"><strong>{file.name}</strong><small>{file.account_email}{file.owner_email ? ` · Owner: ${file.owner_email}` : ''}</small></div><span className="file-size">{formatBytes(file.size)}</span><span className="file-date">{formatDate(file.modified_time)}</span><details className="file-menu"><summary aria-label={`Actions for ${file.name}`}><MoreHorizontal size={20}/></summary><div>{!file.mime_type.startsWith('application/vnd.google-apps.') && <button onClick={() => p.download(file)}><ArrowDownToLine size={15}/> Download</button>}<button onClick={() => p.action('rename', file)}>Rename</button><button onClick={() => p.action('move', file)}>Move</button>{!file.mime_type.startsWith('application/vnd.google-apps.') && <button onClick={() => p.action('copy', file)}>Copy to account</button>}<button onClick={() => p.action('sharing', file)}>Sharing & permissions</button><button className="destructive" onClick={() => p.trash(file)}><Trash2 size={15}/> Move to trash</button></div></details></div>; })}</div>{p.nextOffset != null && <button className="button secondary load-more" onClick={() => void p.load(p.nextOffset!)}>Load more</button>}</> : <Empty icon={<Folder size={22}/>} title="No files to show" description="Connect an account and refresh files, or try a different search or filter." action="Refresh files" onClick={p.sync}/>}
    <div className="files-foot"><span>{p.files.length} files shown</span><span>Files are indexed from your connected accounts.</span></div></section>;
}

function TransfersView({ transfers, refresh, cancel, retry }: { transfers: Transfer[]; refresh: () => void; cancel: (t: Transfer) => void; retry: (t: Transfer) => void }) {
  return <section className="panel"><div className="section-heading"><div><span className="eyebrow">TRANSFER HISTORY</span><h2>In motion</h2></div><button className="button secondary" onClick={refresh}><RefreshCw size={15}/> Refresh</button></div>
    {!transfers.length ? <Empty icon={<Activity size={22}/>} title="No transfers yet" description="Uploads, downloads and account-to-account copies will appear here."/> : <div className="transfer-list">{transfers.map(t => <div className="transfer-row" key={t.id}><span className="file-icon">{t.kind === 'upload' ? <ArrowUpFromLine size={19}/> : t.kind === 'download' ? <ArrowDownToLine size={19}/> : <Copy size={19}/>}</span><div className="transfer-main"><div className="transfer-title"><strong>{t.name}</strong><span className={`badge ${t.status === 'failed' ? 'warn' : ''}`}>{statusLabel(t.status)}</span></div><div className="transfer-meta">{t.kind} · {formatBytes(t.bytes_transferred)} of {formatBytes(t.size)} {t.checksum_verified && '· checksum verified'}</div><div className="progress-track"><span style={{ width: `${t.size ? Math.max(0, Math.min(100, t.bytes_transferred / t.size * 100)) : 0}%` }}/></div>{t.error && <p className="warning-text">{t.error}</p>}<div className="transfer-actions">{active.includes(t.status) && <button className="text-button destructive" disabled={t.status === 'cancel_requested'} onClick={() => cancel(t)}>Cancel</button>}{((t.kind === 'upload' && ['pending', 'in_progress', 'failed'].includes(t.status)) || (t.kind === 'copy' && t.status === 'failed')) && <button className="text-button" onClick={() => retry(t)}>{t.kind === 'upload' ? 'Resume with original file' : 'Retry copy'}</button>}</div></div></div>)}</div>}
    <div className="info-strip"><Activity size={17}/><span>Browser transfers run while this tab is open. Account copies run on the backend and can be retried after a restart.</span></div></section>;
}
function SettingsView({ email, logout }: { email: string; logout: () => void }) {
  return <div className="settings-grid"><section className="panel"><span className="eyebrow">YOUR ACCOUNT</span><h2>CloudFusion login</h2><p className="muted">Signed in as</p><strong>{email}</strong><div className="settings-action"><button className="button secondary" onClick={logout}><LogOut size={16}/> Sign out</button></div></section><section className="panel"><span className="eyebrow">HOW IT WORKS</span><h2>Private by design</h2><p className="muted">CloudFusion creates a virtual view of independent Google Drive accounts. It does not merge storage, and files remain in their destination account.</p><p className="muted">Account-to-account copies stream through your configured backend. Public sharing always requires confirmation.</p><a href="https://myaccount.google.com/permissions" target="_blank" rel="noreferrer" className="text-button">Manage Google access <ExternalLink size={14}/></a></section></div>;
}
