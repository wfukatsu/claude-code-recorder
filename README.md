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

Git から直接インストールする場合は、インストール時にエンジンをビルドするので JDK 17 以降が必要です。

## 使い方

```bash
ccrec whoami                          # 記録に紐づくアカウント
ccrec import ~/.claude/projects/<project>/   # 既存のトランスクリプトを取り込む
ccrec sessions                        # 自分のセッション一覧（新しい順）
ccrec sessions --day 20261007         # 組織の、ある日のセッション一覧
ccrec show <session-id>               # やりとりの一覧（先頭のみ）
ccrec show <session-id> --kind system_prompt --full
ccrec show <session-id> --json
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
- トランスクリプトの形式は Claude Code の内部仕様で、バージョンで変わります。未知の種類のレコードと、既知の種類でも想定と違う形のレコードは、`unknown` として丸ごと保存します。本文を持たない `system` レコード（ターンの所要時間など）もレコードごと残します。保存しないのは、セッション管理用のレコード（モード切り替えなど）と、中身が空のブロックだけです。
- トランスクリプトは 1 行ずつ読むので、大きなファイルの初回取り込みでもメモリは増えません。

### アカウントの決め方（優先順）

1. 環境変数 `CCREC_ACCOUNT_ID`（管理者が配る社員 ID など。`CCREC_ACCOUNT_EMAIL` / `CCREC_ACCOUNT_NAME` / `CCREC_ORG_ID` / `CCREC_ORG_NAME` も指定可）
2. Claude Code にログイン中の OAuth アカウント（`accountUuid` / `organizationUuid`）
3. OS のユーザー名（`local-<user>`）

アカウントはセッションの最初のイベントで確定し、そのセッション中は変わりません。これは端末側の自己申告です。全社で集める場合は、収集側で送信者を認証してアカウントを確定させてください。

### テーブル（名前空間 `ccrec`）

| テーブル | パーティションキー | クラスタリングキー | 内容 |
|---|---|---|---|
| `accounts` | `account_id` | – | メール、表示名、組織 |
| `sessions` | `account_id` | `started_at` 降順, `session_id` | プロジェクト、ブランチ、モデル、タイトル |
| `sessions_by_day` | `org_id`, `day` | `started_at`, `session_id` | 組織 × 日の索引 |
| `messages` | `session_id` | `agent_id`, `line_no`, `block_no` | 種別、ツール名、`message_id`、トークン数、本文のハッシュと先頭 1,000 文字 |
| `contents` | `content_hash` | `chunk_no` | 本文（gzip、6,000 バイト以下のチャンク） |
| `ingest_state` | `host_id` | `source_path_hash` | 取り込み済みの位置 |

`messages.kind` は `user_prompt` / `user_meta` / `assistant_text` / `thinking` / `tool_use` / `tool_result` / `system_prompt` / `tool_definitions` / `context` / `system` / `unknown` です。

Claude Code は 1 回の API 応答をブロックごとの複数行に分けて書き、どの行にも同じトークン数を付けます。トークン数は `message_id` ごとに最初の 1 行にだけ記録するので、そのまま合計できます。

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
npm test        # Node のテストと、SQLite 上の実 ScalarDB を使う Java のテスト
npm pack        # 配布用 .tgz（ビルド込み）
```

- `bin/`, `src/` — Node 製の CLI とフック（依存パッケージなし）
- `engine/` — Java 製の記録エンジン。`RecordStore`（保存の抽象）、`ScalarDbRecordStore`（ScalarDB 実装）、`TranscriptParser`、`Ingester`、`Redactor`、`Syncer`（全社収集への送信口。現状は未実装で何もしません）

依存バージョン（2026-10-07 に各レジストリで確認）: ScalarDB 3.19.1、Shadow 9.6.1、JUnit 6.1.3、Gradle 9.8.0。Jackson 2.18.7 と SLF4J 1.7.36 は ScalarDB 3.19.1 が解決するバージョンに合わせています。
