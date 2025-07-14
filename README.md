#Ship Proxy System

This project provides a proxy system that allows a ship to access the internet using only a single outgoing TCP connection to save satellite costs. All HTTP and HTTPS requests from the ship are handled.

---

## Features
- Proxy Client (Ship Proxy): Listens on port 8080 on the ship.
- Proxy Server (Offshore Proxy): Listens on port 9090 on land.
- Single persistent TCP connection from ship to offshore server.
- Works with browsers and curl.
- Dockerized for easy deployment.

---

## Project Setup

### 1. Clone the Repository

```bash
git clone https://github.com/Aasif201/ship-proxy-system.git
cd ship-proxy-system
```

### 2. Build and Run Using Docker Compose

```bash
docker compose up --build
```

This will:
- Build the Docker images for both components.
- Start both containers.
- Expose port 8080 (ship proxy) and 9090 (offshore proxy).

### 3. Test the Proxy

**For http**:
```bash
curl -x http://localhost:8080 http://httpforever.com/
```

**For https**:
```bash
curl -x http://localhost:8080 https://example.com
```

---

## Use in Your Browser
- Set browser’s proxy setting:

```yaml
HTTP Proxy:  localhost
Port:        8080
```
- All browser traffic will go through the ship proxy.

