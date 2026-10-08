# claude-code-recorder

Claude Code でのやりとり（ユーザープロンプト、システムプロンプト、応答、ツール入出力、注入コンテキスト）を、アカウント単位でデータベースに記録します。保存は [ScalarDB](https://scalardb.scalar-labs.com/) Core（OSS）経由で、ローカルでは SQLite、全社運用では RDBMS / NoSQL に、設定ファイルの差し替えだけで切り替えられます。

## 必要なもの

- Node.js 18 以降
- Java 17 以降（記録エンジンが ScalarDB の Java API を使うため）

## インストール

```bash
npm install -g <このリポジトリ、または npm pack で作った .tgz>
ccrec init            # ~/.ccrec を作成（SQLite 用の設定つき）
ccrec doctor          # Java・エンジン・設定・フック・未記録セッションの確認
ccrec install-hooks   # ~/.claude/settings.json に記録用フックを追加
```

Git から直接インストールする場合は、インストール時にエンジンをビルドします（`prepare` スクリプト）。JDK 17 以降と、Gradle が依存ライブラリを取得するためのネットワーク接続が必要です。

## 使い方

```bash
ccrec whoami                          # 記録に紐づくアカウント
ccrec import ~/.claude/projects/<project>/   # 既存のトランスクリプトを取り込む
ccrec sessions                        # 自分のセッション一覧（新しい順）
ccrec sessions --day 20261007         # 組織の、ある日のセッション一覧
ccrec show <session-id>               # やりとりの一覧（先頭のみ）
ccrec show <session-id> --kind system_prompt --full
ccrec show <session-id> --json
ccrec summary <session-id>            # セッションの要約（金額、所要時間、ターン、ツール、使用量など）
ccrec usage                           # 自分の直近のセッションの、モデル別のトークン使用量
ccrec usage <session-id>              # そのセッションの分
ccrec usage --day 20261007            # 組織の、ある日のセッションの分（アカウント × モデル）
ccrec delete <session-id>             # 記録したセッションを削除する
```

フックを入れた後は、応答のたびに自動で差分が記録されます。フック自体はキューに積むだけ（約 0.04 秒）で、データベースへの書き込みは別プロセスが行います。

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

`messages.kind` は `user_prompt` / `user_meta` / `assistant_text` / `thinking` / `tool_use` / `tool_result` / `system_prompt` / `tool_definitions` / `context` / `system` / `cost` / `pr_link` / `unknown` です。ここに無い種類のブロック（画像など）は `user_<type>` / `assistant_<type>` として保存します。

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
- `--json` のキーは、ほかのコマンドと同じ camelCase です（`costUsd`、`turnDurationMs` など）。`messages.attributes` の中身は、保存した値なので snake_case のままです。
- これらを記録する前に取り込んだ行には付きません。付け直すには、`ccrec delete <session-id>` のあと `ccrec import` で取り込み直してください。

本文は SHA-256 で内容アドレス化しているので、セッションをまたいで同じシステムプロンプトや CLAUDE.md は 1 件にまとまります。

## 設定

`~/.ccrec/config.json`

| キー | 既定 | 意味 |
|---|---|---|
| `recordThinking` | `true` | thinking ブロックを記録する |
| `redact` | `true` | 保存前に既知の形式の認証情報を `[REDACTED]` に置き換える（下記） |

伏せる対象は次の 2 種類です。誤って伏せないことを優先しているので、これ以外の形の認証情報は残ります。

- 接頭辞で見分けられるトークン: Anthropic / OpenAI / AWS アクセスキー ID / GitHub / GitLab / Slack / Google / Stripe / npm / Hugging Face / SendGrid、JWT、PEM 形式の秘密鍵
- 置かれた場所で見分けられる値（値だけを伏せ、前後は残します）: URL 内のパスワード（`postgres://app:[REDACTED]@host/db`）、`Authorization` ヘッダーと `Bearer` の値、Azure の `AccountKey=`、名前が秘密を示す大文字の代入（`DB_PASSWORD=`、`AWS_SECRET_ACCESS_KEY=`、`GITHUB_TOKEN=` など）

記録の除外: 環境変数 `CCREC_DISABLE=1`、またはプロジェクト直下に `.ccrec-ignore` を置く。`.ccrec-ignore` はその配下のどのディレクトリで作業していても効き、一度該当したセッションは最後まで記録されません（`ccrec import` も同じファイルを見ます）。

### 記録の削除

`ccrec delete <session-id>...` は、セッションの行、日別の索引、やりとりの記録、取り込み位置、そしてほかのセッションが使っていない本文を削除します。元に戻せません。

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

## 注意

記録にはソースコードや、伏せきれなかった認証情報が入ります。`~/.ccrec` は所有者のみ読み書きできる権限で作られます。全社で記録する場合は、従業員への周知と保持期間の取り決めが前提です。

## 開発

```bash
npm run build   # engine/ を Gradle でビルドし lib/ccrec-engine.jar を作る
npm test        # Node のテストと、SQLite 上の実 ScalarDB を使う Java のテスト（GitHub Actions でも実行）
npm pack        # 配布用 .tgz（ビルド込み）
```

- `bin/`, `src/` — Node 製の CLI とフック（依存パッケージなし）
- `engine/` — Java 製の記録エンジン。`RecordStore`（保存の抽象）、`ScalarDbRecordStore`（ScalarDB 実装）、`TranscriptParser`、`Ingester`、`Redactor`、`Syncer`（全社収集への送信口。現状は未実装で何もしません）

依存バージョン（2026-10-07 に各レジストリで確認）: ScalarDB 3.19.1、Shadow 9.6.1、JUnit 6.1.3、Gradle 9.8.0。Jackson 2.18.7 と SLF4J 1.7.36 は ScalarDB 3.19.1 が解決するバージョンに合わせています。
