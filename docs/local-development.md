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

## Optional local HTTPS for Melhor Envio

This mode adds a loopback-only TLS reverse proxy, not a public tunnel. It does
not upload the application to a third party or require buying a domain. It also
does not guarantee that the provider's application form accepts a localhost
callback. Check that before completing registration; never substitute a fake
public URL. OAuth redirects are followed by your browser, so the browser must
be on this Mac.

Generate certificates from the repository root using **OpenSSL 3**:

```bash
sh docker/local-https-certificates.sh <store-subdomain>.localhost
```

On macOS, if `openssl version` reports LibreSSL, use an installed OpenSSL 3
on your PATH first. The script retains existing valid certificates and fails
explicitly for incomplete or expiring files. Certificates last one year.
Replace `<store-subdomain>` with your store label (for example,
`sh docker/local-https-certificates.sh palollita.localhost`). You may pass several
store hostnames. Exact names are needed: TLS clients may reject `*.localhost`
wildcards. Rerunning with new names reissues the server certificate using the
same CA; include every desired store name and restart `https` afterward.
The local CA, private keys and generated files stay in ignored `.local/https`.
Never commit or share the private keys. Only the server certificate and key
are mounted into the TLS container, not the CA private key.

Trust the CA on **your own development Mac only**. In Keychain Access, select
your login keychain, import `.local/https/ca.crt`, open **Commerce Platform Local
Development CA**, and under **Trust**, set **When using this certificate** to
**Always Trust**. macOS may ask for your password. This changes browser trust;
the setup script does not change it automatically. Never bypass browser
certificate warnings or trust a CA received from somebody else. Remove this
CA from Keychain Access when you no longer need this development setup.

Start the HTTPS stack:

```bash
docker compose -f compose.yaml -f compose.https.yaml up -d --wait
docker compose -f compose.yaml -f compose.https.yaml watch --no-up
```

Stop an existing Compose Watch first, so it does not recreate services using
the HTTP-only configuration. Include **both files** in subsequent Compose
commands while using HTTPS. The extra proxy listens only on `127.0.0.1:4443`.
Use `https://localhost:4443` for the platform and
`https://<store-subdomain>.localhost:4443` for the storefront. If the subdomain
does not resolve to loopback, add an entry in your hosts file.

Use HTTPS throughout this mode, including login: session cookies become Secure.
The original HTTP ports remain reachable for diagnostics, but HTTP session
mutations are not supported while this override is active. The proxy preserves
the host/port, overwrites forwarded headers, supports Angular's WebSocket
reload, and resolves Docker service names again after Watch replaces containers.
Forwarded-header processing is enabled only by this local override; do not
copy this development networking setup into production.

Verify TLS without disabling certificate validation:

```bash
curl --cacert .local/https/ca.crt https://localhost:4443/
```

For Melhor Envio, choose the host where the merchant will start the connection.
If that is the platform host, the application form values are:

| Field | Local value |
|---|---|
| Platform website | `https://localhost:4443` |
| Test environment | `https://localhost:4443` |
| Redirect/callback URL | `https://localhost:4443/api/public/integrations/melhor-envio/callback` |

If connecting from a store host instead, replace `localhost` with the exact
`<store-subdomain>.localhost` in **all three values**. The host initiating OAuth
must match the callback host; use the exact same callback in the provider and
in the backend configuration, including `:4443`. If the provider rejects local
URLs, stop here: this local-only setup cannot satisfy a public-URL requirement.

The override forwards these optional root `.env` values to the backend:

```dotenv
MELHOR_ENVIO_CLIENT_ID=replace-with-sandbox-client-id
MELHOR_ENVIO_CLIENT_SECRET=replace-with-sandbox-client-secret
MELHOR_ENVIO_REDIRECT_URI=https://localhost:4443/api/public/integrations/melhor-envio/callback
MELHOR_ENVIO_USER_AGENT="CommercePlatform Sandbox (your-contact-email)"
MELHOR_ENVIO_ENCRYPTION_KEY=replace-with-base64-key
```

Generate the encryption key once with `openssl rand -base64 32`, store it
safely, and keep it stable across restarts. It encrypts the stored OAuth tokens;
changing it makes existing credentials unreadable. Keep existing Pix variables
when editing `.env`. Never paste secrets into the frontend, Git, screenshots,
or chat. After changing `.env`, rerun the `up -d --wait` command with both files.
Do not print resolved Compose configuration to share it: it can contain secrets.

After registering the sandbox application and setting the variables, sign into
the **store host** as a merchant and open **Entrega e retirada** at
`/painel/entrega`. Register the full shipping origin; optionally enable pickup
and add customer-facing instructions. Save before starting authorization so
the form is not lost during the external redirect.

Click **Conectar ao Melhor Envio**, sign into the sandbox account, and authorize
the `shipping-calculate` permission. The browser returns to the delivery panel,
which retrieves the actual connection status (the URL message alone is not proof
of a connection). The callback must use this same store host, for example
`https://palollita.localhost:4443/api/public/integrations/melhor-envio/callback`.
Since the panel requires the store host, use the store-host form values above
rather than the platform-host example when configuring this UI.

No personal token needs to be copied: the backend obtains and encrypts access
and refresh tokens through OAuth. Status lookup does not expose credentials.
Disconnecting deletes this platform's stored connection, not the provider
account; access can also be revoked through Melhor Envio itself. This sandbox
integration only quotes freight; it does not purchase freight or print labels.
Provider authorization and real sandbox quotes still require manual validation
with your account.

To return to HTTP development without deleting data:

```bash
docker compose -f compose.yaml -f compose.https.yaml stop https
docker compose up -d --wait
```

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
