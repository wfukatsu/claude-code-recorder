# リファレンス

`ccrec` が何をどう記録するか、設定の詳細、保存先、開発の手引きをまとめた資料です。使い方は [操作マニュアル](manual.md) を見てください。

- [しくみ](#しくみ)
- [設定](#設定)
- [データベースを切り替える](#データベースを切り替える)
- [ブラウザ UI のサーバー](#ブラウザ-ui-のサーバー)
- [開発](#開発)

## しくみ

```
Claude Code ─ フック(SessionStart / Stop / SubagentStop / SessionEnd) ─▶ ~/.ccrec/spool/<session>.json
     └─ トランスクリプト JSONL ─────────────────▶ エンジン(Java) ─ RecordStore ─ ScalarDB ─▶ データベース
```

- 記録元は Claude Code が書くトランスクリプト（`~/.claude/projects/**/<session>.jsonl` とサブエージェント分）です。
- 取り込みは差分かつ冪等です。キーをファイル上の位置から決めるので、何度取り込んでも重複しません。
- 取り込みが走らなかった、または途中で終わったセッション（Java が見つからない、ロック待ちの時間切れなど）は、次にどれかのセッションのフックが動いたときにまとめて記録されます。10 分以上記録されないままのセッションがあると `ccrec doctor` が `FAIL` を返すので、`ccrec ingest` で取り込み直してください。
- 取り込めないキューが 1 件あっても、ほかのセッションは記録されます。読めないキューは `spool/*.json.bad` に退避されます。
- 記録を求められたのにトランスクリプトが見つからないセッションは、未記録のまま残り、`ccrec doctor` に出ます。30 日たっても見つからなければキューから外します。
- トランスクリプトの形式は Claude Code の内部仕様で、バージョンで変わります。未知の種類のレコードと、既知の種類でも想定と違う形のレコードは、`unknown` として丸ごと保存します。本文を持たない `system` レコード（ターンの所要時間など）もレコードごと残します。保存しないのは、セッション管理用のレコード（モード切り替えなど）と、中身が空のブロックだけです。
- トランスクリプトは 1 行ずつ読むので、大きなファイルの初回取り込みでもメモリは増えません。

### アカウントの決め方（優先順）

1. 環境変数 `CCREC_ACCOUNT_ID`（管理者が配る社員 ID など。`CCREC_ACCOUNT_EMAIL` / `CCREC_ACCOUNT_NAME` / `CCREC_ORG_ID` / `CCREC_ORG_NAME` も指定可）
2. Claude Code にログイン中の OAuth アカウント（`accountUuid` / `organizationUuid`）
3. OS のユーザー名（`local-<user>`）

アカウントはセッションの最初のイベントで確定し、そのセッション中は変わりません。記録のあるセッションの続きは、あとから別のアカウントで取り込んでも（ログインの切り替え後の `ccrec import` など）、最初に記録したアカウントに付きます。これは端末側の自己申告です。全社で集める場合は、収集側で送信者を認証してアカウントを確定させてください。

### テーブル（名前空間 `ccrec`）

| テーブル | パーティションキー | クラスタリングキー | 内容 |
|---|---|---|---|
| `accounts` | `account_id` | – | メール、表示名、組織 |
| `sessions` | `account_id` | `started_at` 降順, `session_id` | プロジェクト、ブランチ、モデル、タイトル、起動元、金額、API とツールの所要時間、変更行数 |
| `sessions_by_day` | `org_id`, `day` | `started_at`, `session_id` | 組織 × 日の索引 |
| `sessions_by_id` | `session_id` | – | セッション ID からセッション行を引く索引 |
| `messages` | `session_id` | `agent_id`, `line_no`, `block_no` | 種別、ツール名、`message_id`、トークン数など、付帯情報（`attributes`）、本文のハッシュと先頭 1,000 文字 |
| `contents` | `content_hash` | `chunk_no` | 本文（gzip、6,000 バイト以下のチャンク） |
| `ingest_state` | `host_id` | `source_path_hash` | 取り込み済みの位置 |
| `session_usage` | `session_id` | `model` | セッション × モデルの API 応答数、トークン数、Web 検索・取得回数の合計 |

`messages.kind` は `user_prompt` / `user_meta` / `assistant_text` / `thinking` / `tool_use` / `tool_result` / `system_prompt` / `tool_definitions` / `context` / `system` / `cost` / `pr_link` / `mcp_meta` / `unknown` です。ここに無い種類のブロック（画像など）は `user_<type>` / `assistant_<type>` として保存します。

Claude Code は 1 回の API 応答をブロックごとの複数行に分けて書き、どの行にも同じトークン数を付けます。トークン数は `message_id` ごとに最初の 1 行にだけ記録するので、そのまま合計できます。

### トークン使用量とモデル

- 応答ごとの記録: `messages` の `model` と、`input_tokens` / `output_tokens` / `cache_read_tokens` / `cache_creation_tokens`。`ccrec show` の各行にも表示します。
- セッションごとの合計: `session_usage` に、モデル別の API 応答数とトークン数を持ちます。サブエージェントの分も、そのセッションに含めます。取り込みと同じトランザクションで加算するので、記録とずれません。同じ行を取り込み直しても二重には数えません。
- `ccrec usage` は `ccrec sessions` と同じ絞り込み（`--limit` / `--account` / `--day` / `--org`）を受け取り、対象セッションの合計をアカウント × モデルで表示します。`--json` も使えます。
- `session_usage` が無かった頃に記録したセッションの分は、書き込みを伴うコマンドか `ccrec usage` を最初に実行したときに、`messages` から 1 度だけ集計します。
- トークン数の内訳として、thinking のトークン数（出力の内数）、キャッシュ書き込みの保持時間別（5 分 / 1 時間、キャッシュ書き込みの内数）、Web 検索・Web 取得の回数も、同じ 2 か所に記録します。
- `<synthetic>` は Claude Code が API を呼ばずに作った応答で、トークン数は 0 です。

### そのほかに記録する情報

トランスクリプトの各行が持つ付帯情報を、`messages.attributes` に小さな JSON として保存します。該当する値がある行にだけ付き、文字列は 300 文字で切ります。

| 行の種類 | 項目 |
|---|---|
| 応答 | `stop_reason`、`request_id`、`effort`、`thinking_ms`、`service_tier`、`speed`、`api_error`、`api_error_status`、`skill`、`plugin`、`mcp_server`、`mcp_tool` |
| プロンプト・ツール結果 | `permission_mode`、`prompt_source`、`origin`、`tool_denial`、`interrupted`、`compact_summary`、`file_path`、`lines_added`、`lines_removed`、`status`、`agent_id`、`resolved_model`、`tool_interrupted` |
| `system` | ターンの `duration_ms` と `message_count`、フックの `hook_count` / `hook_errors` / `prevented_continuation`、圧縮の `compact_trigger` / `pre_tokens` / `post_tokens`、実行した `command` |
| `cost` | `cost_usd`、`api_ms`、`tool_ms`、`duration_ms`、`lines_added`、`lines_removed` |
| `pr_link` | `pr_number`、`pr_repository`、`pr_url` |

- 金額（`cost`）は、Claude Code 自身がトランスクリプトに書いた累計です。このツールは単価を持たず、計算もしません。書かれないセッションもあり、その場合は空です。最新の値をセッション行にも持ち、モデル別の内訳は `cost` レコードの本文に入っています。
- `ccrec summary <session-id>` は、これらをセッション単位にまとめて表示します。ターン数と所要時間、終了理由の内訳、API エラー、ツールごとの呼び出し回数とエラー回数、権限モード、使ったスキル・プラグイン・MCP サーバー、作成した PR などです。サブエージェントの分を含みます。
- `summary` の `prompts` は、人または Claude Code を動かすプログラムが送ったプロンプトの数です（`prompt_source` が `typed` / `suggestion_accepted` / `queued` / `sdk`、または `origin` が `human`）。バックグラウンドタスクの通知や圧縮後の要約のように Claude Code 自身が書いた分は数えず、内訳を `promptSources` に出します。
- MCP のツール呼び出しは、ほかのツールと同じく `tool_use`（ツール名は `mcp__<サーバー>__<ツール>`）と `tool_result` として記録します。サーバーがテキストとは別に返した構造化データとメタデータ（`mcpMeta`）は、`mcp_meta` として同じ `tool_use_id` で保存します。`summary` の `mcpServers` は、ツール名から数えたサーバーごとの呼び出し回数です。MCP サーバーとの通信そのものや、サーバー側の所要時間は記録しません。
- `--json` のキーは、ほかのコマンドと同じ camelCase です（`costUsd`、`turnDurationMs` など）。`messages.attributes` の中身は、保存した値なので snake_case のままです。
- これらを記録する前に取り込んだ行には付きません。付け直すには、`ccrec delete <session-id>` のあと `ccrec import` で取り込み直してください。

本文は SHA-256 で内容アドレス化しているので、セッションをまたいで同じシステムプロンプトや CLAUDE.md は 1 件にまとまります。

## 設定

`~/.ccrec/config.json`

| キー | 既定 | 意味 |
|---|---|---|
| `recordThinking` | `true` | thinking ブロックを記録する |
| `redact` | `true` | 保存前に既知の形式の認証情報を `[REDACTED]` に置き換える（下記） |
| `exclude` | なし | 記録しない種類の一覧。`kind`、または `kind/subtype` で指定する |
| `localMcpServers` | なし | この端末の中だけで動く MCP サーバーの名前の一覧。ブラウザ UI の「外部通信」から外す。記録には影響しない |

`exclude` の例です。ほかのフックの実行結果（`context/hook_success`）は、フックを多く入れた環境では記録の半分近くを占めます。

```json
{ "recordThinking": true, "redact": true, "exclude": ["context/hook_success"] }
```

設定は次の取り込みから効きます。記録済みの分は消えません。セッションにどの種類が何件あるかは、`ccrec show <session-id> --json` の `kind` と `subtype` で確かめられます。

伏せる対象は次の 2 種類です。誤って伏せないことを優先しているので、これ以外の形の認証情報は残ります。

- 接頭辞で見分けられるトークン: Anthropic / OpenAI / AWS アクセスキー ID / GitHub / GitLab / Slack / Google / Stripe / npm / Hugging Face / SendGrid、JWT、PEM 形式の秘密鍵
- 置かれた場所で見分けられる値（値だけを伏せ、前後は残します）: URL 内のパスワード（`postgres://app:[REDACTED]@host/db`）、`Authorization` ヘッダーと `Bearer` の値、Azure の `AccountKey=`、名前が秘密を示す大文字の代入（`DB_PASSWORD=`、`AWS_SECRET_ACCESS_KEY=`、`GITHUB_TOKEN=` など）

記録の除外: 環境変数 `CCREC_DISABLE=1`、またはプロジェクト直下に `.ccrec-ignore` を置く。`.ccrec-ignore` はその配下のどのディレクトリで作業していても効き、一度該当したセッションは最後まで記録されません（`ccrec import` も同じファイルを見ます）。

### 記録の削除

`ccrec delete <session-id>...` は、セッションの行、日別の索引、やりとりの記録、取り込み位置、そしてほかのセッションが使っていない本文を削除します。元に戻せません。

- `--before <yyyy-mm-dd>` または `--older-than <日数>d` を付けると、最後の動きがそれより前のセッションをまとめて削除します。対象は自分のアカウントの分です（`--account` で変更可）。`--dry-run` は対象を表示するだけで、何も消しません。日付はこの端末のタイムゾーンで、その日の 0 時より前が対象です。自動で消す機能はないので、定期的に消すなら cron などから実行してください。
- 同じシステムプロンプトのように、ほかのセッションも参照している本文は残します。参照の有無は `messages` を 1 回全件読んで確かめるので、記録が多いほど時間がかかります。
- 削除したセッションは、フックからは以後記録されません（`spool/<session>.ignored` を置きます）。`ccrec import` で明示的に取り込めば、最初から記録し直します。
- トランスクリプト（`~/.claude/projects/` 配下の JSONL）は消しません。
- SQLite のファイルサイズは減りません。空いた領域は以後の記録に再利用されます。ファイルからも痕跡を消すには、`sqlite3 ~/.ccrec/ccrec.sqlite3 VACUUM` を実行してください。
- 途中で止まった場合は、同じコマンドをもう一度実行すれば残りを削除します。

## データベースを切り替える

`~/.ccrec/database.properties` は ScalarDB の設定ファイルそのものです。書き換えるだけで記録先が変わります。

```properties
scalar.db.storage=jdbc
scalar.db.contact_points=jdbc:postgresql://db.example.com:5432/ccrec
scalar.db.username=ccrec
scalar.db.password=********
scalar.db.transaction_manager=consensus-commit
```

- 同梱の JAR に入っている JDBC ドライバーは SQLite / PostgreSQL / MariaDB です。それ以外（MySQL、Oracle など）は `~/.ccrec/drivers/` に JAR を置きます。
- DynamoDB / Cosmos DB / Cassandra などを使う場合は、全アダプター入りの JAR をビルドします（約 170MB）。`npm run build:full` で `lib/ccrec-engine-full.jar` ができるので、`CCREC_JAR` でそれを指定します。
- ScalarDB は SQLite を開発・テスト用途としています。このツールは書き込みを 1 プロセスに直列化して使いますが、全社の集約先には使わないでください。
- SQLite ファイルを直接開くと、テーブル名は `ccrec$messages` の形で、ScalarDB のトランザクション用の列が付いています。直接書き込まないでください。

## ブラウザ UI のサーバー

`ccrec ui` のサーバーは、エンジン（Java）の中で JDK 標準の HTTP サーバーとして動きます。画面は HTML / CSS / JavaScript のファイルで、JAR に同梱しています。

記録にはソースコードや、伏せきれなかった認証情報が入っています。そのため、サーバーは次のように動きます。

- `127.0.0.1` だけで待ち受けます。ほかの端末からは接続できません。
- 起動のたびにランダムなトークンを発行し、トークンのない要求には応えません。表示されるアドレスにはトークンが入っているので、人に渡さないでください。
- `Host` ヘッダーが `127.0.0.1:<port>` か `localhost:<port>` でない要求は拒否します。
- データを変える操作は、セッションの削除だけです。削除の要求には、トークンに加えて、このページのスクリプトにしか出せない形（JSON、専用のヘッダー、同じオリジン）を求めます。
- 記録された文字は、画面にテキストとしてだけ差し込みます。HTML として解釈することはありません。Markdown の整形も、要素を組み立てて文字を入れる方式です。リンクになるのは `http://` と `https://` で始まる URL だけです。
- 応答には `Content-Security-Policy: default-src 'self'` を付けます。インラインのスクリプトとスタイルは使っていません。

### 外部通信の判定

「外部通信」は、記録済みのツール呼び出しを表示のときに判定します（`dev.ccrec.net.NetworkUse`）。データベースには何も足さないので、以前に記録したセッションでも使えます。判定を変えれば、過去の分の見え方も変わります。

- アドレスを持つツール（`url` の入力）は、そのホストを送信先にします。
- `mcp__<サーバー>__<ツール>` は、入力にアドレスがあればそのホスト、なければサーバーを送信先にします。端末内で動くと分かっているサーバー（`claude-in-chrome`、`playwright`、`serena` など）と、設定の `localMcpServers` に書かれたサーバーは、アドレスが無ければ数えません。
- シェルコマンドは、プログラムごとに読みます。常に通信するプログラム（`curl`、`gh`、`ssh` など）と、特定のサブコマンドで通信するプログラム（`git push`、`npm install` など）を見分け、その引数にあるアドレスを送信先にします。ヒアドキュメントの本文は読み飛ばします。
- ループバック、プライベートアドレス、ドットの無いホスト名、Claude のドメインは、送信先から外します。

通信を捕捉しているわけではないので、スクリプトの内部からの通信は見つけられません。

API は次のとおりです。どれもトークンが必要です。

| メソッドとパス | 内容 |
|---|---|
| `GET /api/accounts` | 記録のあるアカウントと、起動した人のアカウント |
| `GET /api/sessions?account=&limit=` | アカウントのセッション（新しい順、最大 1,000 件）と、それぞれの使用量 |
| `GET /api/sessions/{id}` | セッションの要約（`ccrec summary --json` と同じ内容に、種類別の件数とエージェントの一覧を足したもの） |
| `GET /api/sessions/{id}/records?kinds=&agent=&order=&offset=&limit=` | レコードのページ（最大 500 件）。本文は先頭 300 文字。外部に届いたツール呼び出しには `network`（経路、送信先、送信内容の 1 行）が付く |
| `GET /api/sessions/{id}/records?network=1&host=&categories=&order=&offset=` | 外部に届いたツール呼び出しと、その結果だけのページ |
| `GET /api/sessions/{id}/network` | 外部に届いたツール呼び出しの、送信先ごとの集計 |
| `GET /api/content/{hash}` | 本文の全文 |
| `GET /api/status` | 状態の画面の内容 |
| `POST /api/sessions/delete` | セッションの削除（`{"sessionIds": [...]}`、最大 50 件） |

## 開発

```bash
npm run build   # engine/ を Gradle でビルドし lib/ccrec-engine.jar を作る
npm test        # Node のテストと、SQLite 上の実 ScalarDB を使う Java のテスト（GitHub Actions でも実行）
npm pack        # 配布用 .tgz（ビルド込み）
```

- `bin/`, `src/` — Node 製の CLI とフック（依存パッケージなし）
- `engine/` — Java 製の記録エンジン。`RecordStore`（保存の抽象）、`ScalarDbRecordStore`（ScalarDB 実装）、`TranscriptParser`、`Ingester`、`Redactor`、`Syncer`（全社収集への送信口。現状は未実装で何もしません）

依存バージョン（2026-10-07 に各レジストリで確認）: ScalarDB 3.19.1、Shadow 9.6.1、JUnit 6.1.3、Gradle 9.8.0。Jackson 2.18.7 と SLF4J 1.7.36 は ScalarDB 3.19.1 が解決するバージョンに合わせています。

### リリース

`v<バージョン>` のタグをプッシュすると、GitHub Actions がビルドとテストを行い、`npm pack` で作ったパッケージを GitHub のリリースに添付します（`.github/workflows/release.yml`）。

```bash
npm version minor          # package.json を更新し、コミットとタグを作る
git push --follow-tags
```

- タグと `package.json` のバージョンが一致しないと、リリースは失敗します。
- `engine/build.gradle` の `version` も合わせて更新してください。
- パッケージにはビルド済みのエンジンが入るので、インストールする側に JDK は要りません（実行には Java 17 以降が必要です）。
- `package.json` に `"private": true` を入れているので、`npm publish` はできません。npm のレジストリに公開する場合は、この行を外してください。
