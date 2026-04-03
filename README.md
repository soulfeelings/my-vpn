# VLESS VPN Android App

A simple Android VPN client that connects via the VLESS protocol using Xray core.

## Features
- One-tap connect/disconnect
- VLESS protocol support
- TLS and Reality security
- Configurable transport (TCP, WebSocket, gRPC, H2)
- uTLS fingerprint support
- Flow control (xtls-rprx-vision)

## Setup
1. Open in Android Studio
2. Sync Gradle dependencies
3. Build and install on device
4. Go to Settings, enter your VLESS server details
5. Tap Connect

## Configuration
In the Settings screen, configure:
- **Server Address** — your VLESS server IP or domain
- **Server Port** — typically 443
- **UUID** — your VLESS user ID
- **Network** — TCP, WebSocket, gRPC, or H2
- **Security** — TLS, Reality, or None
- **SNI** — server name for TLS
- **Flow** — xtls-rprx-vision (optional)

## Requirements
- Android 7.0+ (API 24)
- Android Studio Hedgehog or newer
