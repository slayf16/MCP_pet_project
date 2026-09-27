# Rick and Morty MCP server

One Kotlin/JVM Streamable HTTP MCP server with one read-only tool:
`search_rick_and_morty_characters`.

## Requirements

- JDK 21 (set `JAVA_HOME` to that JDK when several versions are installed)
- network access to Maven Central for the first Gradle run

## Local run

```powershell
./gradlew.bat clean test installDist
$env:MCP_BIND_HOST = "127.0.0.1"
$env:MCP_PORT = "3000"
./gradlew.bat run
```

The MCP endpoint is `http://127.0.0.1:3000/mcp`.

### Session and Host/Origin limits

The endpoint remains unauthenticated. The MCP SDK's DNS rebinding protection is
on for `/mcp`. By default it accepts `127.0.0.1` and `localhost` hosts, and
origins `http://127.0.0.1:<port>` and `http://localhost:<port>`. For a reverse
proxy, set the public host and HTTPS origin explicitly; values are comma-separated:

```text
MCP_ALLOWED_HOSTS=mcp.example.com
MCP_ALLOWED_ORIGINS=https://mcp.example.com
MCP_MAX_SESSIONS=32
MCP_SESSION_IDLE_SECONDS=300
```

`MCP_PORT` defaults to `3000` only when unset or blank; a non-numeric value stops
startup with a configuration error. Sessions are in memory only. An initialized
session is closed after the configured idle period, excess initialize requests
receive HTTP 503, and shutdown closes all remaining transports. No business data
is persisted.

## Tool contract

`search_rick_and_morty_characters` requires one string input, `name`. The name
is trimmed and must be 1–100 characters. The server performs one GET to the
official character endpoint, does not send `page`, retries nothing, and stops
waiting after 10 seconds.

It returns only the first page (at most 20 cards). Each card contains `id`,
`name`, `status`, `species`, and `imageUrl`; metadata includes total counts and
whether another page exists. A valid search with no matches succeeds with an
empty result. Other upstream failures return an MCP tool error without raw
response bodies.

## MCP smoke

With the server running:

```powershell
./gradlew.bat smoke --args "http://127.0.0.1:3000/mcp rick"
```

The smoke command performs MCP initialize, verifies the exact single-tool schema,
calls it, verifies first-page metadata and each minimal card, and exits nonzero
on transport, discovery, output-contract, or tool errors.

## Ubuntu deployment

Build `installDist`, then install each distribution as an immutable release:

```text
/opt/mcp-pet-server/releases/<release-id>/   # copied installDist directory
/opt/mcp-pet-server/current -> releases/<release-id>
```

Create the unprivileged `mcp-pet-server` user, copy the install directory to
`releases/<release-id>`, make it readable by that service group, and update the
`current` symlink. Only after `current` exists, copy
`deploy/mcp-pet-server.service.example` to
`/etc/systemd/system/mcp-pet-server.service`, run `systemctl daemon-reload`,
and enable the service. The unit deliberately starts
`/opt/mcp-pet-server/current/bin/MCP_pet_project`.

Put bind, Host/Origin, and optional session limits in
`/etc/mcp-pet-server.env`, for example:

```text
MCP_BIND_HOST=127.0.0.1
MCP_PORT=3000
MCP_ALLOWED_HOSTS=mcp.example.com
MCP_ALLOWED_ORIGINS=https://mcp.example.com
```

Use a stable public HTTPS proxy URL ending in `/mcp` for a remote Codex host.
After deployment, configure it with:

```text
codex mcp add rickMorty --url https://your-host.example/mcp
codex mcp list
```

Those commands only register the endpoint. Verify initialize, `tools/list`, and
one real tool call in a new Codex session. Do not put an SSH key, token, or VPS
address into this repository.
