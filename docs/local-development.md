# Docker development environment

This Compose configuration is for local development, not production deployment.
It preserves the Modular Monolith: one backend, one Angular development server
and one PostgreSQL database. Containers make the environment reproducible; they
do not introduce microservices or automatic application scaling.

## Requirements and startup

Install and start Docker Desktop (or Docker Engine with Compose on Linux).
Docker Compose **2.32 or newer** is required for Watch initial synchronization.
No host Java, Maven, Node.js or PostgreSQL installation is needed.
Stop existing processes on ports 4200 and 8080 before starting.

From the repository root:

```bash
docker compose up --build --watch
```

Compose waits for PostgreSQL and backend health before starting the frontend.
The first build downloads images and dependencies and can take several minutes.
Build and startup failures remain visible in the terminal.

Open `http://localhost:4200/painel/cadastro` to create a merchant and store.
Then use `http://<store-subdomain>.localhost:4200` for that store. The browser
must resolve these names to loopback; configure a hosts entry for each store
if your system does not resolve `*.localhost`.

The container database starts empty and is **separate from any database on the
host**. Existing local accounts and products are not imported automatically.
Flyway applies all committed migrations on backend startup.

## Editing code

Compose Watch synchronizes Angular source and public assets into the frontend
container. Angular polls for changes and reloads the browser automatically,
including on Docker Desktop. Dependency/configuration changes rebuild the image;
`npm ci` always follows the committed lockfile.

Backend source, resources, migrations and POM changes rebuild and recreate only
the backend container. This automatically recompiles Java without depending on
the IDE's automatic compilation. Maven downloads are cached by BuildKit.
The backend is temporarily unavailable while rebuilding; frontend API failures
remain visible and can be retried after startup. Backend restarts do not clear
the database or signing key. Editing an applied Flyway migration is not supported;
add a new migration instead.

Watch must stay running in the terminal. `docker compose up --build` without
`--watch` starts the same services but does not follow source edits. Changes to
`compose.yaml` itself require stopping and running the startup command again.

## Networking, sessions and secrets

Frontend 4200, backend 8080 and PostgreSQL 5433 are published only on
`127.0.0.1`. Containers still reach PostgreSQL as `database:5432`. The frontend
proxy forwards `/api/**` to `backend:8080` while preserving the original Host.
The browser continues using a single origin for API calls, cookies and WebSockets.
Store resolution, exact Origin validation and host-only session cookies therefore
work with store subdomains. Host access to `/actuator/health` reports aggregate
backend/database health without exposing component details or other actuator APIs.

An initialization service generates random database and JWT secrets on first
startup in a Docker volume, without printing them or writing them into the
repository. Existing secrets are retained on subsequent starts. Backend and
database mount the secrets read-only. Secrets in local Docker volumes are **not**
a production secret manager and can be read by users with Docker access.

`STORE_SESSION_COOKIE_SECURE=false` is set only in this development Compose file
because localhost uses HTTP. The application default remains secure; production
requires HTTPS, secure cookies, a secret manager, a production Angular build and
a hardened reverse proxy instead of these development servers/images.
Session data lives in PostgreSQL, the JWT key survives rebuilds, and reloading
the browser restores login through the existing HttpOnly refresh cookie.

S3 photo storage and Melhor Envio remain optional external integrations; this
stack does not simulate or configure them. Their existing environment settings
and valid credentials are required to enable uploads and shipping integration.
Do not commit credentials or add them to Docker build arguments.

## Operations and data persistence

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
docker compose down
```

Ctrl+C stops the foreground stack; `down` also removes its containers/network.
Neither removes the database or generated secrets. Start with the same project
name and working directory to reuse the volumes. Docker's database is not a
backup: back it up separately before storing important development data.

**Destructive reset:** `docker compose down --volumes` deletes this Compose
project's database and secrets. All Docker-local accounts, carts, orders and
sessions are lost; the next startup generates a new signing key and empty
database. Do not delete only the secrets volume while keeping the database:
its stored database password would no longer match the new secret.

## DBeaver connection on macOS

Create a separate PostgreSQL connection named `Commerce - Docker`; do not
replace the connection to the host's existing PostgreSQL.

| Field | Value |
|---|---|
| Host | `127.0.0.1` |
| Port | `5433` |
| Database | `commerce_platform` |
| Username | `commerce` |
| Password | Generated secret from the Docker volume |

With the stack running, copy the database password to the macOS clipboard
from a terminal at the repository root:

```bash
docker compose exec -T database cat /run/dev-secrets/database-password | pbcopy
```

Paste it into DBeaver's password field, click **Test Connection** (allow the
PostgreSQL driver download if prompted), then **Finish**. Tables are under
`commerce_platform > Schemas > public > Tables`. The application and DBeaver
now access the same Docker database; the original Mac database remains separate.
Do not paste this password into chat, commit it or share terminal output.
After entering it, clear the clipboard with `pbcopy < /dev/null`.

The port mapping does not change passwords or migrate/duplicate data. If port
5433 is occupied, stop the conflicting service or change only the host port
in Compose and use that same port in DBeaver. Backend database settings stay
unchanged. After editing Compose, stop Watch with Ctrl+C and run
`docker compose up --watch` again to apply the port mapping; no image rebuild
or volume deletion is required.

For frontend checks inside the running stack:

```bash
docker compose exec frontend npm test -- --watch=false
docker compose exec frontend npm run build
```

The backend integration suite uses Testcontainers or an explicitly configured
dedicated test database. Do not point it at this development database; normal
startup does not run tests or modify the host's test configuration.
