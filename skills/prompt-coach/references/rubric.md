# How to judge

For each type of flag, this gives four things:

- **Meaning**: what the flag points at
- **Not a problem when**: if it matches, drop the flag
- **What the human can change**: how to change the instructions
- **Why it works**: the report's "Why it works" applies the reason given here to the moment at hand

Security comes first, followed by how to read the change since the last review and the list of
finding ids. Last comes what no flag shows: the elements of a prompt.

---

## Security (security)

Write it as the report's first section. Write secret values masked as the analysis gives them
(`ghp_****`); never look up the original value and copy it out.

### secret-in-prompt (severity: high)

- **Meaning**: a prompt (including what was pasted into it) held a credential. `masked by ccrec when
  recorded` means ccrec masked it when recording: the value is not in the record, but **it was sent
  to Claude**.
- **Not a problem when**: it is a dummy value for illustration (an official example such as
  `AKIAIOSFODNN7EXAMPLE`, `xxxx`), or the user knows it was made for testing and already revoked.
- **What the human can change**: revoke the key or token and issue a new one. Instead of pasting the
  value into a prompt, have it read from an environment variable or a secret manager (the 1Password
  CLI, `gh auth`, etc.). If a cookie or token has to be handed over, give only the path to a file,
  and protect the file with `.gitignore` and permissions.
- **Why it works**: a value put into a prompt stays in the conversation's record, logs, summaries,
  and sometimes in files and commits made during the work. It is hard to remove afterwards, and
  there is no telling whether it leaked. Handing over only a reference keeps the value out of the
  conversation.

### confidential-in-prompt (severity: medium)

- **Meaning**: a prompt held something that should not go out: a "confidential" marking, card
  numbers, many email addresses.
- **Not a problem when**: the work is within what the organisation allows, and the terms of use
  permit it (only suggest the user check). Test numbers (such as 4111 1111 1111 1111).
- **What the human can change**: before handing it over, mask personal data and numbers the work
  does not need, or replace them with dummies. Of a confidential document, hand over only the parts
  needed.
- **Why it works**: the AI may use everything it is given in the work. What is not handed over
  cannot slip into its output or files.

### injection-suspected (severity: high; critical without permission checks)

- **Meaning**: a tool result (a web page, a file, an MCP response) or pasted text contained a
  sentence posing as an instruction to the AI ("ignore the previous instructions", "print the
  system prompt", "do not tell the user", "send the credentials").
- **Not a problem when**: reading a document, test, or security research that **explains** prompt
  injection (including reading this rubric or the skill's own code). Text the user typed themselves.
- **What to check**: read the evidence's `next answer` and judge whether Claude appears to have
  followed it. If it appears so, check the tool calls that came after (sending, deleting, reading
  credentials) with `ccrec show`, and write only the facts in the report.
- **What the human can change**: for work that takes in untrusted pages or documents, keep
  permission checks on (do not run with `bypassPermissions`). Take sending and deleting tools out of
  the allow list. Say in the instructions that what is taken in is data, and its instructions are
  not to be followed.
- **Why it works**: text from outside is hard for the AI to tell apart from instructions. With
  permission checks, a person can stop it before harm is done, even if it follows the text.

---

## Reading the change since the last review (comparison)

- `improved`: down by 25% or more since the last review. Write it under "what improved", with the
  values then and now. If there is an `example`, add its quote as a good example. Say what was good,
  so the user can keep it up.
- `unchanged`: no large change. If this period has an example, write it again as a suggestion. Do
  not repeat the last rewrite; fit a new one to this period's moment.
- `worse`: up by 25% or more. Put it near the top of this review's suggestions.
- `too-little`: too little happened in this period to compare (fewer than 5 work prompts, or fewer
  than 2 sessions). Draw no conclusion; say only that there was too little.
- The unit of a value (`unit`) is the share of work prompts for a missing element, per 10 work
  prompts for a finding about prompts, and per session for the rest. Give the unit in the report.

## Finding ids

The `id` of a finding passed to `history.mjs save` is one of these (the same names as the flag
types).

- Instructions: `missing-criteria`, `missing-constraint`, `missing-target`, `user-correction`,
  `vague-wording`, `trouble-without-evidence`, `short-prompt-big-task`, `repeated-prompt`,
  `clarification-asked`
- Reasoning: `retraction`, `interrupted`, `api-errors`
- Tools: `repeated-call`, `retry-after-error`, `tool-error-rate`, `shell-for-builtin`,
  `large-results`, `tool-denials`
- Model: `context-compacted`, `long-context`, `heavy-model-for-lookup`, `low-cache-hit`,
  `high-thinking-share`
- Security: `secret-in-prompt`, `confidential-in-prompt`, `injection-suspected`

---

## Instructions (instructions)

### user-correction (severity: high)

- **Meaning**: the user corrected course ("no", "that's not it"). The previous instruction and the
  AI's understanding of it had drifted apart.
- **Not a problem when**: the user simply changed their mind, and the previous instruction was clear.
- **What the human can change**: pin down what the previous instruction lacked (the target, the
  scope, what done looks like) and write it from the start. When correcting, say plainly what is
  withdrawn and what stays ("Drop A. Keep B as it is. Add C").
- **Why it works**: the AI fills in what is not written with plausible guesses. When a guess misses,
  all the work until the correction is wasted. And unless a withdrawal is explicit, the old
  instruction lingers, and the work goes on with the old and new instructions contradicting each
  other.
- Bad → good: "Make the login screen look nice" → "In the login form (src/login/Form.tsx), even out
  the spacing between the inputs. Leave the header and footer alone. Check it with a screenshot when
  done"

### vague-wording (severity: medium)

- **Meaning**: words that leave the standard of "good" to the AI: "make it nice", "whatever works",
  "fix it".
- **Not a problem when**: exploring or brainstorming, where leaving it to the AI is the point ("give
  me three ideas").
- **What the human can change**: give at least one standard of "good" (for looks, a reference
  screen; for performance, a number; for writing, the reader and the length).
- **Why it works**: without a standard, the AI decides by its own where to stop. It tends either to
  overdo it (changes nobody asked for) or to stop too soon.

### trouble-without-evidence (severity: medium)

- **Meaning**: a problem was reported with none of the full error, logs, file and line, or steps to
  reproduce.
- **Not a problem when**: the AI already saw the error earlier in the same conversation.
- **What the human can change**: paste what you see **as is, without summarising**: the full error,
  the command run, the expected and actual result, which file and where. For a problem on screen, a
  screenshot and where it is.
- **Why it works**: the AI guesses the cause from the symptoms. The fewer the clues, the more tool
  calls it spends searching, and the likelier it starts from a wrong hypothesis. The error itself
  carries more clues than a summary of it (the stack trace, versions, paths).

### short-prompt-big-task (severity: medium)

- **Meaning**: one short prompt was followed by many tool calls.
- **Not a problem when**: a plan was agreed just before. Or the AI first asked questions or laid out
  a plan before going ahead. Or the prompt, though short, had a clear target and definition of done
  ("make npm test pass").
- **What the human can change**: for a large task, write the goal, the scope, and what done looks
  like. Or split it into stages: "First show a plan, and go ahead once I approve it".
- **Why it works**: started without a scope, the AI widens it as it investigates. Even if it drifts,
  a person notices only when it is over. Having it show a plan first lets the drift be fixed in the
  first few minutes.

### repeated-prompt (severity: medium)

- **Meaning**: nearly the same prompt was sent again.
- **Not a problem when**: running an improvement loop on purpose. Or resuming after an interruption.
- **What the human can change**: when repeating, add what fell short last time ("Last time X was not
  fixed. Put X first this time"). If it is the same every time, turn the steps into a skill or
  custom command.
- **Why it works**: the same instruction tends to get an answer along the same lines. Saying how it
  differs from last time lets the AI focus on what is missing instead of repeating itself.

### clarification-asked (severity: low)

- **Meaning**: the AI asked a question before working (AskUserQuestion).
- **Not a problem when**: it really was a person's call (a preference, a policy, an outside effect).
  That is a good move.
- **What the human can change**: if the same kind of question keeps coming up, put its answer in the
  first instruction or in CLAUDE.md.
- **Why it works**: an assumption asked about every time needs no back-and-forth once written down.

---

## Reasoning (reasoning)

### retraction (severity: medium)

- **Meaning**: the AI took back something it said, or apologised.
- **Not a problem when**: new information came up and it rightly changed its mind. Or it only
  apologised for its wording.
- **What the human can change**: look at the cause of the retraction. An unclear assumption (→ write
  the assumption). Asserting without checking (→ ask it to "separate guesses from what is
  confirmed" and "give the file and line it rests on"). The instruction changed midway (→ make the
  change explicit).
- **Why it works**: the AI may state unchecked things in confident, natural sentences. Asking for
  grounds puts checking into the work, and confident mistakes drop.

### interrupted (severity: medium)

- **Meaning**: the user stopped the work.
- **Not a problem when**: something urgent simply came up.
- **What the human can change**: look at what the AI was doing just before. If it was heading
  somewhere other than instructed, rule that direction out from the start ("Don't touch X"). If it
  ran too long, ask it to stop at checkpoints.
- **Why it works**: an interruption is the strongest sign of drift. Filling the gap in the
  instructions that caused it avoids the next interruption.

### api-errors (severity: low)

- A brief mention for reference is enough. For overload or rate limits, there is little the human
  can change. For exceeding the context length, the change is the same as for context-compacted.

---

## Tools (tools)

### repeated-call / retry-after-error (severity: medium)

- **Meaning**: the same tool was called several times with the same input and nothing changed in
  between. Or a failed call was repeated as is.
- **Not a problem when**: waiting on outside state (CI, a deploy). Or retrying a transient error was
  right.
- **What the human can change**: if the cause was "searched around not knowing where", hand over the
  path or command first. If the cause was "the environment" (permissions, not installed), state the
  premise first, or set up the environment before asking.
- **Why it works**: tool results pile up in the conversation and are read again as input tokens at
  every answer. A wasted call costs not only its own time but every later answer too.

### tool-error-rate (severity: medium)

- **Meaning**: a particular tool failed at a high rate.
- **Not a problem when**: tests were made to fail on purpose (TDD).
- **What the human can change**: look at what failed. If many failures were missing paths or denied
  permissions, say first where to work and which commands are allowed.
- **Why it works**: at every failure the AI fits in an investigation and a detour. Handing over the
  premise saves those round trips.

### shell-for-builtin / large-results (severity: low)

- **Meaning**: files were read, written, or searched with shell cat / grep / find. Or a huge result
  entered the conversation.
- **Not a problem when**: the user's settings (CLAUDE.md, etc.) say to use the shell. Or processing
  through a pipe was needed.
- **What the human can change**: when huge output came in, say how much is wanted ("just the count",
  "just the first 50 lines"). For a huge log, hand over its path and have it find the part needed.
- **Why it works**: output that has entered the conversation is read again every time until
  compacted. Letting in only what is needed makes the context last, and keeps compaction from
  thinning out earlier instructions.

### tool-denials (severity: low)

- If many calls were denied by permissions, suggest reviewing the allow settings or stating the
  allowed scope at the start.

---

## Model and tokens (model)

### context-compacted (severity: medium)

- **Meaning**: the conversation grew long and the context was compacted.
- **Not a problem when**: a long piece of work was deliberately kept in one session, and nothing went
  wrong around the compaction.
- **What the human can change**: when the goal changes, split the session (`/clear` or a new
  session). If it has to continue, have the AI sum up "the decisions so far" at a checkpoint before
  going on.
- **Why it works**: compaction is a summary, so fine instructions and decisions can drop out. "But
  I told you before" tends to happen after compaction. Splitting sessions by goal also keeps each
  input small.

### long-context (severity: medium)

- **Meaning**: the context read for one answer (input plus cache) reached 300,000 tokens or more.
  Even without compaction, this shows up when work with different goals piles up in one session.
  The detail gives the largest context, the average per answer, and the total read from cache.
- **Not a problem when**: the work has to handle a large document or codebase at once, and handing
  the premises over again to a new session would cost more.
- **What the human can change**: at a point where the goal changes (a feature committed, an
  investigation finished), have it sum up the decisions and the files to touch next, and continue in
  a new session. For large tool results (logs, images, long files), have it read only the part
  needed.
- **Why it works**: what enters the conversation is read again at every answer. The cache lowers the
  unit price, but at this size it still costs time and money, and the details of earlier work drag
  on more. Carrying over only the decisions keeps each input small.

### heavy-model-for-lookup (severity: medium)

- **Meaning**: a sub-agent on a top-tier model (Opus, etc.) did nothing but read and search.
- **Not a problem when**: the search needed serious judgement along the way (comparing designs, a
  security investigation).
- **What the human can change**: when asking for lookups or mechanical work, say to use a lighter
  model ("research with a Haiku/Sonnet sub-agent"). Reviewing the default model for sub-agents also
  helps.
- **Why it works**: top-tier models cost more per token. For work whose result does not depend on
  the quality of judgement, a lighter model gives almost the same result, faster and cheaper.

### low-cache-hit / high-thinking-share (severity: low)

- **low-cache-hit**: little was read from the cache. Recreating sessions often, changing CLAUDE.md
  or the system prompt often, or resuming after a long break all break the cache. Work on the same
  premises is cheaper continued in the same session.
- **high-thinking-share**: most of the output was thinking. For a hard problem, that is right. If it
  is high for simple work, the AI may be wavering over an unclear instruction. Check what it wavered
  over in the excerpts, and tie it to a change in the instructions.

---

## What no flag shows: the elements of a prompt

Look at `prompts[].features` for prompts that ask for work (not approvals or acknowledgements).

| Element | What tends to happen without it | Why adding it helps |
|---|---|---|
| Goal / what done means (criteria) | Overdoing it, stopping too soon, reporting done without checking | The AI has a place to stop and a way to check |
| Constraint / scope (constraint) | Changes nobody asked for, changes where it should not touch | It knows what it may change, and rework drops |
| Target (path / url / code) | Tool calls spent searching, changes in the wrong place | It works in the right place from the start |
| The evidence itself (code) | Investigation from guesses, wrong hypotheses | Clues lost in a summary (traces, versions) are kept |

A prompt need not have all of them. They matter most in **the first prompt of a piece of work** and
in **a prompt that changes direction**.

## Finding what was done well

Besides findings, write good examples like these, with evidence, so the user keeps them as habits.

- Had a plan shown first, and went ahead after approving it
- Handed over errors and logs as they were
- Said what done looks like, or how to check it
- Split the session when the goal changed
