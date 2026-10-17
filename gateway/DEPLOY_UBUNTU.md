# MyFilmTV v0.9 — HLS playlist gateway

The Android TV player sends only `https://v<number>.rstu6.com/.../*.m3u8` requests through the configured gateway. The gateway rewrites nested playlist references; all segment and key URLs remain direct.

## VPS deployment (Ubuntu)

Use the existing file `gateway/hls_gateway.py`. It listens only on `127.0.0.1:8765`.

1. Create a dedicated non-root system user and copy `hls_gateway.py` to `/opt/myfilm-gateway/`.
2. Generate a secret locally: `openssl rand -hex 32`. Do not add the secret to Git or share it.
3. Place these environment variables in a root-readable file with mode 0600:

```ini
MYFILM_GATEWAY_PUBLIC_URL=https://sg3.zlb35.dns.army
MYFILM_GATEWAY_TOKEN=REPLACE_WITH_SECRET
```

4. Start the Python process using systemd. Use `EnvironmentFile=/etc/myfilm-gateway.env`, `ExecStart=/usr/bin/python3 /opt/myfilm-gateway/hls_gateway.py`, `User=myfilm-gateway`, `Restart=on-failure`. Enable and start it with systemctl.
5. Configure your EXISTING HTTPS reverse proxy to forward requests on `https://sg3.zlb35.dns.army` to `127.0.0.1:8765`. Do not overwrite any Xray, Hysteria2, or preexisting Caddy/Nginx listener; check ownership of ports 80/443 first.
6. Verify `curl -i https://sg3.zlb35.dns.army/health` returns HTTP 200 with `ok`.
7. In the Android TV application settings, enter the gateway URL and the same secret. The sources.json URL remains unchanged.

Security notes: Access secrets are sent in playlist URLs. Treat URLs and logs as sensitive; avoid query string access logs on the reverse proxy. TLS is required. Restrict any internet-facing HTTPS router/firewall to intended access. This gateway is not a generalized forward proxy, but it can request matching URLs and should be kept updated.

Known limitations: Does not redirect or proxy segments, non-m3u8 key URLs, or non-rstu6 playlists. Playback depends on direct access to segments and supported HLS manifests. No live video playback or GitHub Actions build is asserted by this PR.
