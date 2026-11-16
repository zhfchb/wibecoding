"""Optional proxy for vector providers; internal callbacks keep their own routing."""
import os
from functools import lru_cache

import httpx
from httpx_socks import SyncProxyTransport


@lru_cache
def get_vector_http_client():
    proxy = os.environ.get('VECTOR_PROXY_URL', '').strip()
    if not proxy:
        return None
    # rdns=False resolves the destination on the client, while retaining the
    # original hostname for TLS SNI and certificate validation.
    rdns = os.environ.get('VECTOR_PROXY_RDNS', 'false').lower() == 'true'
    transport = SyncProxyTransport.from_url(proxy, rdns=rdns, trust_env=False)
    return httpx.Client(transport=transport, trust_env=False, timeout=45)
