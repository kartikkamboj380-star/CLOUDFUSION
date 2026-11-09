"""Direct HTTP resources only. Pin every connection to a validated public DNS result.

No proxies, cookies, credentials, automatic redirects, decompression or private egress.
Deployment must additionally enforce an outbound firewall (defence in depth).
"""
import http.client
import ipaddress
import socket
from contextlib import contextmanager
from urllib.parse import urljoin, urlsplit


class RemoteError(ValueError):
    pass


def public_target(url):
    if len(url) > 8192 or any(ord(c) <= 32 or ord(c) == 127 for c in url):
        raise RemoteError("Invalid download URL")
    try:
        p = urlsplit(url)
        port = p.port or (443 if p.scheme == "https" else 80)
        host = p.hostname
    except ValueError as exc:
        raise RemoteError("Invalid download URL") from exc
    if p.scheme not in ("https", "http") or not host or p.username is not None or p.password is not None or p.fragment:
        raise RemoteError("Use a direct HTTP/HTTPS URL without credentials or fragments")
    if port not in (80, 443):
        raise RemoteError("Only standard HTTP/HTTPS ports are supported")
    try:
        records = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        addresses = list(dict.fromkeys(record[4][0] for record in records))
    except (OSError, UnicodeError) as exc:
        raise RemoteError("Source hostname could not be resolved") from exc
    if not addresses:
        raise RemoteError("Source hostname could not be resolved")
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if not ip.is_global or ip.is_multicast or getattr(ip, "ipv4_mapped", None) or getattr(ip, "sixtofour", None) or getattr(ip, "teredo", None):
            raise RemoteError("Private, local, metadata and special-purpose addresses are blocked")
    return p, port, addresses[0]


@contextmanager
def open_source(url, method="GET", headers=None):
    connection = None
    try:
        for _ in range(6):
            p, port, address = public_target(url)
            cls = http.client.HTTPSConnection if p.scheme == "https" else http.client.HTTPConnection
            connection = cls(p.hostname, port, timeout=30)
            # TLS still checks the original hostname, but DNS cannot change the socket IP.
            connection._create_connection = lambda target, timeout, source_address=None: socket.create_connection((address, port), timeout, source_address)
            path = p.path or "/"
            if p.query:
                path += "?" + p.query
            connection.request(method, path, headers={"Accept-Encoding": "identity", "User-Agent": "CloudFusion/0.3", **(headers or {})})
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader("Location")
                if not location:
                    raise RemoteError("Source returned an invalid redirect")
                target = urljoin(url, location)
                if p.scheme == "https" and urlsplit(target).scheme != "https":
                    raise RemoteError("HTTPS downgrade redirect blocked")
                connection.close()
                url = target
                continue
            yield response
            return
        raise RemoteError("Too many source redirects")
    except (OSError, http.client.HTTPException) as exc:
        raise RemoteError("Source network request failed or timed out") from exc
    finally:
        if connection:
            connection.close()
