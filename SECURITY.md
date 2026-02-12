# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT** open a public GitHub issue
2. Send a detailed description to the project maintainers via private channels
3. Include steps to reproduce, impact assessment, and suggested fix if possible

We will acknowledge receipt within 48 hours and provide a timeline for a fix.

## Security Considerations

KGBoard communicates with OpenRGB via a local TCP connection (default `127.0.0.1:6742`).
The OpenRGB SDK protocol has no built-in authentication or encryption.

**Recommendations:**
- Keep OpenRGB bound to localhost only (default behavior)
- Do not expose the OpenRGB SDK port to untrusted networks
- Review firewall rules if running on a shared machine
