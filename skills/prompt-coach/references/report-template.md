# Prompt review (<target> / <period>)

> Target: <account>, <this session's title, or "all sessions (n)">, <start>–<end> (<local time zone>).
> Previous review: <date and time, or "none">. Made from the ccrec recordings. Quotes are as recorded (including masks applied when recording). Secret values are masked.

## Summary

- Security: <how many found and the most severe; if none, "nothing found">
- Improved since the last review: <1 sentence; leave this line out if there was no previous review>
- The change that would help most: <1 sentence>
- A good habit to keep: <1 sentence>

## Security

If nothing was found, write only "No secrets or confidential material in prompts, and no suspected
prompt injection, were found."

### <severity> <kind, e.g. "A GitHub token in a prompt">

- **Where**: <session title> (<time>), <prompt / paste / WebFetch result (URL), etc.>
- **What**: <masked, e.g. "GitHub token: ghp_****"; for injection, the sentence in question>
- **Then**: <for injection, whether Claude appears to have followed it; for a secret, whether it was masked when recorded>
- **What to do**: <revoke and reissue the key, review the permission mode, etc., concretely>
- **Why**: <2–3 sentences>

## Since the last review

Leave this section out if there was no previous review.

| Previous finding | Then | Now | Verdict |
|---|---|---|---|
| <heading> | <value and unit> | <value and unit> | improved / unchanged / worse / too little to judge |

### What improved

- **<heading>**: <value then> → <value now>. <quote of a good example from this period (session, time)>. <what was good, in 1 sentence>

## In numbers

| Measure | Value | How to read it |
|---|---|---|
| Output tokens (by model) | <model>: <n> | <only what the judgement used> |
| Share read from cache | <n>% | |
| Sessions compacted | <n> / <n> | |
| Prompts that asked for work | <n> | with what done means <n>, with a constraint <n>, with a target <n> |

Leave out numbers the judgement did not use.

## Suggestions

From what happened in this period. If a previous finding is "unchanged" or "worse", say so under its
heading.

### 1. <heading: the change, as a verb phrase, e.g. "Report a failure with the error itself">

- **Aspect**: instructions / reasoning / tools / model
- **Came up**: <n> sessions, <n> times
- **Last review**: <new / unchanged / worse>

**What happened**

<session title> (<YYYY-MM-DD HH:mm>)

> <quote of the prompt>

<what the AI did next: the number of tool calls, retractions, corrections — facts only, in 2–3 sentences>

**What in the instructions led to it**

<1–2 sentences>

**A better way to put it**

```text
<a rewrite fitted to that moment>
```

**Why it works**

<the reason, in terms of how the AI goes about the work, in 2–4 sentences>

---

(2. onwards in the same form, up to 5–8 in all)

## Done well

- **<heading>**: <the session and quote it rests on> — <why it was good>

## For reference (little to do with the instructions)

- <API errors, tool failures, etc., one per line>

## Sessions reviewed

| Date | Title | Prompts | Tool calls | Output tokens |
|---|---|---|---|---|
| <YYYY-MM-DD> | <title> | <n> | <n> | <n> |
