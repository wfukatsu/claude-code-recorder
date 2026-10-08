# Reference

English | [日本語](reference.ja.md)

What `ccrec` records and how, the settings in detail, where it records, and notes for development. For how to use it, see the [manual](manual.md).

- [How it works](#how-it-works)
- [Settings](#settings)
- [Changing the database](#changing-the-database)
- [The browser UI's server](#the-browser-uis-server)
- [Development](#development)

## How it works

```
Claude Code ─ hooks (SessionStart / Stop / SubagentStop / SessionEnd) ─▶ ~/.ccrec/spool/<session>.json
     └─ transcript JSONL ─────────────────▶ engine (Java) ─ RecordStore ─ ScalarDB ─▶ database
```

- The source is the transcript Claude Code writes (`~/.claude/projects/**/<session>.jsonl`, and the sub-agents' beside it).
- Ingesting is incremental and idempotent. Keys come from a record's position in its file, so reading a file again never duplicates anything.
- A session whose ingest did not run or did not finish (Java not found, the wait for the lock timed out) is recorded together with the next session whose hook fires. When a session has gone unrecorded for more than 10 minutes, `ccrec doctor` reports `FAIL`; `ccrec ingest` records it.
- One queue entry that cannot be ingested does not hold back the others. An unreadable entry is set aside as `spool/*.json.bad`.
- A session an ingest was asked for whose transcript cannot be found stays unrecorded and shows in `ccrec doctor`. After 30 days without the transcript it is dropped from the queue.
- The transcript format is internal to Claude Code and changes between versions. A record of a kind this tool does not know, or of a known kind in an unexpected shape, is kept whole as `unknown`. A `system` record without content (a turn's duration, say) is kept whole too. Only session bookkeeping (a change of mode, for instance) and empty blocks are not kept.
- A transcript is read a line at a time, so a first import of a large file does not grow in memory.

### How the account is decided (in order)

1. The environment variable `CCREC_ACCOUNT_ID` (an employee id handed out by an administrator, say; `CCREC_ACCOUNT_EMAIL`, `CCREC_ACCOUNT_NAME`, `CCREC_ORG_ID` and `CCREC_ORG_NAME` can be given too)
2. The OAuth account Claude Code is logged in with (`accountUuid` / `organizationUuid`)
3. The operating system's user name (`local-<user>`)

The account is fixed at a session's first event and does not change during it. The rest of a session that already has records is filed under the account that first recorded it, even when it is ingested later under another (a `ccrec import` after a change of login, for instance). This is the machine's own statement: when collecting across a company, authenticate the sender on the collecting side and decide the account there.

### Tables (namespace `ccrec`)

| Table | Partition key | Clustering key | Holds |
|---|---|---|---|
| `accounts` | `account_id` | – | Email, display name, organization |
| `sessions` | `account_id` | `started_at` descending, `session_id` | Project, branch, model, title, entrypoint, cost, time in the API and in tools, lines changed |
| `sessions_by_day` | `org_id`, `day` | `started_at`, `session_id` | An index by organization and day |
| `sessions_by_id` | `session_id` | – | An index from a session id to its row |
| `messages` | `session_id` | `agent_id`, `line_no`, `block_no` | Kind, tool name, `message_id`, token counts, attributes, the content's hash and its first 1,000 characters |
| `contents` | `content_hash` | `chunk_no` | The content (gzip, in chunks of at most 6,000 bytes) |
| `ingest_state` | `host_id` | `source_path_hash` | How far each file has been ingested |
| `session_usage` | `session_id` | `model` | Per session and model: API messages, tokens, web searches and fetches |

`messages.kind` is one of `user_prompt`, `user_meta`, `assistant_text`, `thinking`, `tool_use`, `tool_result`, `system_prompt`, `tool_definitions`, `context`, `system`, `cost`, `pr_link`, `mcp_meta` and `unknown`. A block of a type not among these (an image, for instance) is stored as `user_<type>` or `assistant_<type>`.

Claude Code writes one API message as several lines, a block each, and puts the same token counts on every one of them. The counts are recorded on the first line of each `message_id` only, so they can simply be added up.

### Token usage and models

- Per message: `model` in `messages`, with `input_tokens`, `output_tokens`, `cache_read_tokens` and `cache_creation_tokens`. `ccrec show` prints them on each row.
- Per session: `session_usage` holds API messages and tokens per model, the sub-agents' included. They are added in the transaction that records the messages, so the two never drift apart, and a line read again is not counted twice.
- `ccrec usage` takes the same selection as `ccrec sessions` (`--limit`, `--account`, `--day`, `--org`) and shows the total of those sessions per account and model. `--json` works too.
- For sessions recorded before `session_usage` existed, the sums are made once from `messages`, the first time a command that writes — or `ccrec usage` — runs.
- As a breakdown, thinking tokens (part of output), cache writes by lifetime (5 minutes and 1 hour, parts of cache creation), and web searches and fetches are recorded in the same two places.
- `<synthetic>` is a response Claude Code made without calling the API; its counts are 0.

### What else is recorded

What a transcript line says about itself is stored in `messages.attributes` as a small JSON object. Only lines that have such values carry it, and strings are cut at 300 characters.

| Line | Attributes |
|---|---|
| A response | `stop_reason`, `request_id`, `effort`, `thinking_ms`, `service_tier`, `speed`, `api_error`, `api_error_status`, `skill`, `plugin`, `mcp_server`, `mcp_tool` |
| A prompt or a tool result | `permission_mode`, `prompt_source`, `origin`, `tool_denial`, `interrupted`, `compact_summary`, `file_path`, `lines_added`, `lines_removed`, `status`, `agent_id`, `resolved_model`, `tool_interrupted` |
| `system` | A turn's `duration_ms` and `message_count`; the hooks' `hook_count`, `hook_errors` and `prevented_continuation`; a compaction's `compact_trigger`, `pre_tokens` and `post_tokens`; the `command` that was run |
| `cost` | `cost_usd`, `api_ms`, `tool_ms`, `duration_ms`, `lines_added`, `lines_removed` |
| `pr_link` | `pr_number`, `pr_repository`, `pr_url` |

- The cost (`cost`) is the running total Claude Code itself writes into the transcript. This tool has no prices and computes nothing. Claude Code does not write it for every session; where it does not, the cost is empty. The latest figures are also on the session's row, and the breakdown by model is in the content of the `cost` record.
- `ccrec summary <session-id>` gathers these per session: turns and their duration, stop reasons, API errors, calls and errors per tool, permission modes, the skills, plugins and MCP servers used, pull requests opened, and so on, the sub-agents' included.
- `prompts` in the summary counts what somebody, or a program driving Claude Code, sent (`prompt_source` of `typed`, `suggestion_accepted`, `queued` or `sdk`, or `origin` of `human`). What Claude Code writes itself — a background task's notification, the summary after a compaction — is not counted, and the breakdown is given as `promptSources`.
- An MCP tool call is recorded like any other, as `tool_use` (named `mcp__<server>__<tool>`) and `tool_result`. The structured data and metadata a server returns beside the text (`mcpMeta`) is stored as `mcp_meta` under the same `tool_use_id`. `mcpServers` in the summary is calls per server, counted from the tool names. The MCP traffic itself and a server's own timings are not recorded.
- The keys of `--json` output are camelCase like the other commands' (`costUsd`, `turnDurationMs`). What is inside `messages.attributes` is stored data and stays snake_case.
- Lines ingested before these were recorded do not carry them. To get them, `ccrec delete <session-id>` and then `ccrec import` the session again.

Content is addressed by its SHA-256, so the same system prompt or CLAUDE.md is stored once across sessions.

## Settings

`~/.ccrec/config.json`

| Key | Default | Meaning |
|---|---|---|
| `recordThinking` | `true` | Record thinking blocks |
| `redact` | `true` | Replace credentials of well-known shapes with `[REDACTED]` before storing (see below) |
| `exclude` | none | Kinds not to record, each a `kind` or a `kind/subtype` |
| `localMcpServers` | none | Names of MCP servers that run on this machine only; left out of the browser UI's Network tab. It does not affect what is recorded |

An example of `exclude`. What other hooks print (`context/hook_success`) is close to half of all records where many hooks are installed.

```json
{ "recordThinking": true, "redact": true, "exclude": ["context/hook_success"] }
```

Settings take effect from the next ingest. What is already recorded is not removed. `kind` and `subtype` in `ccrec show <session-id> --json` show which kinds a session has and how many.

Two sorts of thing are masked. It prefers leaving something in over masking by mistake, so credentials of other shapes remain.

- Tokens told by their prefix: Anthropic, OpenAI, AWS access key ids, GitHub, GitLab, Slack, Google, Stripe, npm, Hugging Face, SendGrid; JWTs; PEM private keys
- Values told by where they stand (only the value is masked, what surrounds it stays): a password inside a URL (`postgres://app:[REDACTED]@host/db`), the values of an `Authorization` header and of `Bearer`, Azure's `AccountKey=`, an upper-case assignment whose name says it is a secret (`DB_PASSWORD=`, `AWS_SECRET_ACCESS_KEY=`, `GITHUB_TOKEN=` and the like)

Leaving things out of the recording: the environment variable `CCREC_DISABLE=1`, or a file `.ccrec-ignore` at the top of a project. `.ccrec-ignore` applies in every directory below it, and a session it applied to once is not recorded to its end (`ccrec import` honours the same file).

### Deleting recordings

`ccrec delete <session-id>...` removes the session's row, its by-day index row, its records, how far its files were ingested, and every content no other session uses. It cannot be undone.

- With `--before <yyyy-mm-dd>` or `--older-than <days>d`, the sessions whose last activity is older are deleted together. It covers your own account (`--account` changes that). `--dry-run` lists them and deletes nothing. A date is taken in this machine's time zone and means before that day began. Nothing deletes on a schedule: run it from cron or the like if you want that.
- Content other sessions also refer to, a shared system prompt for instance, stays. Whether it is referred to is found by reading `messages` once in full, so it takes longer the more is recorded.
- A deleted session is not recorded by the hooks again (`spool/<session>.ignored` is left). An explicit `ccrec import` records it afresh from the start.
- The transcripts (the JSONL under `~/.claude/projects/`) are not deleted.
- The SQLite file does not shrink. The space freed is used again by later recordings. To remove the traces from the file as well, run `sqlite3 ~/.ccrec/ccrec.sqlite3 VACUUM`.
- If it is interrupted, running the same command again deletes the rest.

## Changing the database

`~/.ccrec/database.properties` is a ScalarDB configuration file as it is. Rewriting it is all it takes to record somewhere else.

```properties
scalar.db.storage=jdbc
scalar.db.contact_points=jdbc:postgresql://db.example.com:5432/ccrec
scalar.db.username=ccrec
scalar.db.password=********
scalar.db.transaction_manager=consensus-commit
```

- The bundled JAR has the JDBC drivers for SQLite, PostgreSQL and MariaDB. For others (MySQL, Oracle and so on), put the driver's JAR in `~/.ccrec/drivers/`.
- For DynamoDB, Cosmos DB, Cassandra and the like, build the JAR with every adapter (about 170 MB): `npm run build:full` makes `lib/ccrec-engine-full.jar`; point `CCREC_JAR` at it.
- ScalarDB treats SQLite as a development and test store. This tool uses it with writes serialized into one process at a time, but do not use it as the place a company's recordings are gathered.
- Opened directly, the SQLite file has tables named like `ccrec$messages`, with ScalarDB's transaction columns. Do not write to it directly.

## The browser UI's server

The server behind `ccrec ui` runs inside the engine (Java), on the JDK's own HTTP server. The pages are HTML, CSS and JavaScript files bundled in the JAR.

Recordings contain source code, and credentials the masking missed. So the server works like this:

- It listens on `127.0.0.1` only. Nothing else on the network can connect.
- It issues a random token at each start and answers no request without it. The address it prints carries the token: do not hand it to anyone.
- It refuses a request whose `Host` header is not `127.0.0.1:<port>` or `localhost:<port>`.
- The only operation that changes data is deleting sessions. Beyond the token, a deletion has to be a request only this page's script can make: JSON, a header of its own, the same origin.
- Recorded text goes into the page as text only, never parsed as HTML. The Markdown layout, too, builds elements and puts text in them. Only URLs that start with `http://` or `https://` become links.
- Responses carry `Content-Security-Policy: default-src 'self'`. There is no inline script or style.

### Telling what reached beyond this machine

The Network tab works out, when it is shown, which recorded tool calls reached beyond this machine and Claude (`dev.ccrec.net.NetworkUse`). Nothing is added to the database, so sessions recorded before show it too — and a change to the rules changes how past sessions look.

- A tool that takes an address (a `url` in its input) goes to that host.
- `mcp__<server>__<tool>` goes to the host of an address in its input if there is one, to the server otherwise. Servers known to run on the machine (`claude-in-chrome`, `playwright`, `serena` and so on) and those listed under `localMcpServers` in the settings are not counted when the input has no address.
- A shell command is read program by program: programs that always talk to the network (`curl`, `gh`, `ssh` and so on) are told from those that do for some subcommands (`git push`, `npm install` and so on), and the addresses in their arguments are the destinations. The body of a here-document is skipped.
- Loopback and private addresses, host names without a dot, and Claude's own domains are not destinations.

This does not capture traffic, so what a script does on its own is not seen.

The API is as follows. Every request needs the token.

| Method and path | Returns |
|---|---|
| `GET /api/accounts` | The accounts that have recordings, and the account of whoever started the UI |
| `GET /api/sessions?account=&limit=` | An account's sessions (newest first, at most 1,000) with the usage of each |
| `GET /api/sessions/{id}` | The session's summary (what `ccrec summary --json` prints, plus counts per kind and the list of agents) |
| `GET /api/sessions/{id}/records?kinds=&agent=&order=&offset=&limit=` | A page of records (at most 500). Text is its first 300 characters. A tool call that reached out carries `network` (the way out, the destinations, what was sent in a line) |
| `GET /api/sessions/{id}/records?network=1&host=&categories=&order=&offset=` | A page of only the tool calls that reached out, and their results |
| `GET /api/sessions/{id}/network` | The tool calls that reached out, summed per destination |
| `GET /api/content/{hash}` | A content in full |
| `GET /api/status` | What the Status screen shows |
| `POST /api/sessions/delete` | Deletes sessions (`{"sessionIds": [...]}`, at most 50) |

## Development

```bash
npm run build   # build engine/ with Gradle into lib/ccrec-engine.jar
npm test        # the Node tests, and the Java tests on a real ScalarDB over SQLite (also run by GitHub Actions)
npm pack        # the package (.tgz), built
```

- `bin/`, `src/` — the CLI and the hook, in Node, with no dependencies
- `engine/` — the recording engine, in Java: `RecordStore` (the storage port), `ScalarDbRecordStore` (its ScalarDB implementation), `TranscriptParser`, `Ingester`, `Redactor`, and `Syncer` (the way out to a company-wide collector; not implemented, it does nothing)

Dependency versions (checked against each registry on 2026-10-07): ScalarDB 3.19.1, Shadow 9.6.1, JUnit 6.1.3, Gradle 9.8.0. Jackson 2.18.7 and SLF4J 1.7.36 are the versions ScalarDB 3.19.1 itself resolves.

### Documents

The documents are in English, with a Japanese version beside each: `README.md` and `README.ja.md`, `docs/manual.md` and `docs/manual.ja.md`, `docs/reference.md` and `docs/reference.ja.md`. Each has a link to the other at its top. Screenshots are under `docs/images/en/` and `docs/images/ja/`. A change to one language's document belongs in the other as well.

### Releasing

Pushing a tag `v<version>` makes GitHub Actions build, test, and attach the package made by `npm pack` to a GitHub release (`.github/workflows/release.yml`).

```bash
npm version minor          # updates package.json, commits and tags
git push --follow-tags
```

- The release fails when the tag does not match the version in `package.json`.
- Update `version` in `engine/build.gradle` to match.
- The package carries the built engine, so whoever installs it needs no JDK (running it needs Java 17 or later).
- `package.json` has `"private": true`, so `npm publish` is refused. To publish to the npm registry, remove that line.
