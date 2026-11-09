import { describe, expect, it } from 'vitest';
import { formatBytes, googleAuthorizationUrl, googleDriveLink } from './api';

describe('storage display', () => {
  it('uses actual values and handles unavailable quotas', () => {
    expect(formatBytes(null)).toBe('Unavailable');
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1024)).toBe('1.0 KiB');
    expect(formatBytes(1_610_612_736)).toBe('1.5 GiB');
  });
});
describe('external links', () => {
  it('restricts authorization and Drive links to HTTPS Google origins', () => {
    expect(googleAuthorizationUrl('https://accounts.google.com/o/oauth2/v2/auth')).toBe(true);
    expect(googleAuthorizationUrl('https://accounts.google.com.evil.test/')).toBe(false);
    expect(googleDriveLink('javascript:alert(1)')).toBeNull();
    expect(googleDriveLink('https://drive.google.com/file/d/abc')).toBeTruthy();
  });
});

describe('API validation errors', () => {
  it('shows a safe validation message without returning submitted input', async () => {
    const { request } = await import('./api');
    const original = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ detail: [{ msg: 'Password must be longer' }] }), { status: 422 });
    try {
      await expect(request('', '/api/auth/register', { method: 'POST', json: { password: 'not-valid' } })).rejects.toThrow('Password must be longer');
    } finally {
      globalThis.fetch = original;
    }
  });
});
