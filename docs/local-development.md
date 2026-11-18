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

If the browser shows `502 Bad Gateway` during Watch, run `docker compose ps`
and wait for `backend` to be `healthy` before retrying. If it stays unavailable,
run `docker compose logs --tail=60 backend frontend`. A gateway error is not a
session expiry. If **Sair da conta** receives a gateway error, retry once the
backend is healthy so it can revoke the session and clear the HttpOnly cookie.

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

## Mercado Pago Pix sandbox

Pix is off by default. For the first local test, you only need a Mercado Pago
account and one application for the platform. You do not need a seller account
per store, a transfer, or a public webhook URL.

1. Open [Suas integrações](https://www.mercadopago.com.br/developers/panel/app).
   Sign into your Mercado Pago account or create one if needed.
2. Click **Criar aplicação**. Give it a name such as `Commerce Platform - Teste`.
   If asked, select **Pagamentos online** and **Desenvolvimento próprio**.
   Select **Checkout Transparente**, then **Orders** as the integrated API
   (not **Payments**, **Checkout Pro**, or **Link de pagamento**).
3. Open the application, select **Teste** at the top, and go to **Credenciais**
   or the **Credenciais** card on **Início**. Copy **Access Token**, not
   **Public Key**. Verify the environment label: the token prefix is not enough.
   See [the dashboard reference](https://www.mercadopago.com.br/developers/pt/docs/your-integrations/application-details)
   if the layout differs.
4. At the repository root (the folder containing `compose.yaml`), run
   `nano .env`. Add the lines below, replacing `cole-o-token-de-teste-aqui`.
   Keep other settings in the file, and do not duplicate the keys. In Nano,
   press `Ctrl+O`, `Enter`, then `Ctrl+X` to save and exit.

```dotenv
PIX_SANDBOX_ENABLED=true
MERCADO_PAGO_TEST_ACCESS_TOKEN=cole-o-token-de-teste-aqui
```

The root `.env` is ignored by Git. Do not paste credentials in chat, logs, or
frontend files. Never use a production token: the sandbox ticket check runs
**after** the provider request.

Stop Compose Watch with `Ctrl+C` if it is running, then apply the configuration:

```bash
docker compose up -d --build --wait
docker compose watch --no-up
```

Open `http://<subdomínio-da-loja>.localhost:4200`, sign in as a customer, and
place an order for an in-stock item with a positive total. On the order page,
click **Gerar Pix de teste**, wait a few seconds, then click **Atualizar estado
do pedido**. Mercado Pago's [Pix test flow](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/integration-test/pix)
simulates approval automatically. **Do not scan or pay the QR with a banking app.**
A reservation made at 14:00 with a Pix issued at 14:05 ends at 14:35, not
15:00. Reload, retry, and status refresh do not restart the timer. A confirmation
after stock release requires manual review, without stock deduction or fulfillment.

**Optional: automatic confirmation via webhook.** For a separate webhook
test, make only the test webhook route available through a controlled public
HTTPS endpoint; Mercado Pago cannot reach `localhost`. In the application's
test environment, open **Webhooks** (under **Notificações**, if shown),
configure `https://<test-host>/api/public/payments/mercado-pago/webhook`, enable
the **Orders** topic, and save. Copy the signing secret to
`MERCADO_PAGO_WEBHOOK_SECRET` in the same `.env` and apply the Compose commands
above. Leave the secret unset to keep the webhook disabled while testing via
manual refresh.

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
