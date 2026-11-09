export type Account = {
  id: string; email: string; reauthorization_required: boolean; quota_available: boolean;
  quota_error: string | null; limit_bytes: number | null; used_bytes: number | null;
  free_bytes: number | null; unlimited: boolean; reserved_bytes: number; quota_checked_at: string | null;
};
export type DriveFile = {
  id: string; account_id: string; account_email: string; name: string; mime_type: string;
  size: number | null; modified_time: string | null; owner_email: string | null;
  md5_checksum: string | null; web_view_link: string | null; parents: string[];
};
export type Transfer = {
  id: string; kind: 'upload' | 'copy' | 'download'; status: string; name: string; size: number;
  bytes_transferred: number; destination_account_id: string | null; source_account_id: string | null;
  result_file_id: string | null; result_md5: string | null; checksum_verified: boolean | null;
  error: string | null; created_at: string;
};
export type Dashboard = {
  connected_accounts: number; capacity_bytes: number | null; used_bytes: number | null;
  remaining_bytes: number | null; utilization_percent: number | null; active_transfers: number;
  failed_transfers: number; accounts: Account[]; recent_files: DriveFile[]; recent_transfers: Transfer[];
};
export type Page<T> = { items: T[]; next_offset: number | null };
export type Permission = { id: string; type: string; role: string; emailAddress?: string };
export type Sharing = { permissions: Permission[]; file: { webViewLink?: string } };

export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message); }
}
export async function request<T>(token: string, path: string, options: { method?: string; json?: unknown; bytes?: ArrayBuffer; offset?: number; signal?: AbortSignal } = {}): Promise<T> {
  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (options.json !== undefined) headers.set('Content-Type', 'application/json');
  if (options.bytes) headers.set('Content-Type', 'application/octet-stream');
  if (options.offset !== undefined) headers.set('Upload-Offset', String(options.offset));
  const response = await fetch(path, {
    method: options.method || 'GET', headers,
    body: options.json !== undefined ? JSON.stringify(options.json) : options.bytes,
    signal: options.signal,
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    const detail = typeof data.detail === 'string' ? data.detail : Array.isArray(data.detail)
      ? data.detail.map((item: { msg?: string }) => item.msg || 'Invalid value').join('; ')
      : data.detail?.message || 'Request failed';
    throw new ApiError(detail, response.status);
  }
  return response.status === 204 ? undefined as T : response.json();
}

export function formatBytes(value: number | null | undefined): string {
  if (value == null || value < 0) return 'Unavailable';
  if (value < 1024) return `${value} B`;
  const exponent = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 4);
  return `${(value / 1024 ** exponent).toFixed(1)} ${['B', 'KiB', 'MiB', 'GiB', 'TiB'][exponent]}`;
}
export function googleAuthorizationUrl(value: string): boolean {
  try { const url = new URL(value); return url.protocol === 'https:' && url.hostname === 'accounts.google.com'; } catch { return false; }
}
export function googleDriveLink(value: string | null | undefined): string | null {
  if (!value) return null;
  try { const url = new URL(value); return url.protocol === 'https:' && (url.hostname === 'drive.google.com' || url.hostname === 'docs.google.com') ? value : null; } catch { return null; }
}
