# Runtime egress policy input

[`egress-hosts.json`](./egress-hosts.json) is the complete application-layer HTTPS host inventory for the v0.2.5 backend. Region-data source and license URLs are metadata only and are never fetched at runtime. The Firebase issuer URL is used only as a JWT claim value.

This inventory is **not enforcement**. Docker Compose does not provide a portable FQDN egress allowlist, and these providers may rotate addresses. Do not translate the file into permanent static-IP firewall rules. Production release remains blocked until the actual host/container network topology is inventoried and a DNS-aware host, proxy, or network policy enforces:

- TCP 443 only to the four exact hostnames in the inventory;
- DNS only to the operator-approved resolver required by that enforcement mechanism;
- default-deny application-container egress to every other destination;
- no transparent HTTP proxy that records URLs, query strings, credentials, or coordinates;
- AMap console restriction to the verified production egress IP.

Before release, prove allowed connections succeed and a connection to a non-inventory hostname fails from the same container network namespace. Also prove that blocking AMap returns a safe unavailable response without Google fallback and blocking Google does not redirect traffic to AMap. Record only hostnames, status/error codes, and timestamps; never record full upstream URLs or request/response bodies.
