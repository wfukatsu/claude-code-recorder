'use strict';

// How recorded text is laid out for reading: Markdown, the wrappers Claude Code puts around what it
// adds to a prompt, and tool inputs. Everything here builds nodes and sets their text; nothing is
// ever parsed as HTML, whatever the recorded text looks like.

const Render = (() => {
  function h(tag, className, ...children) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  // ---- Markdown, inline ---------------------------------------------------------------------

  // Code first, so that nothing inside it is taken for emphasis; a link only to http(s).
  const INLINE =
    /(`+)([\s\S]*?[^`])\1(?!`)|\*\*([^\s*](?:[^*]*[^\s*])?)\*\*|\*([^\s*](?:[^*]*[^\s*])?)\*|~~([^~]+)~~|\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)|(https?:\/\/[^\s<>"'`)\]]+)/g;

  function link(text, url) {
    const a = h('a', 'md-link', text);
    a.href = url;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    return a;
  }

  function inline(text) {
    const out = [];
    let last = 0;
    for (const match of text.matchAll(INLINE)) {
      if (match.index > last) out.push(text.slice(last, match.index));
      if (match[1]) out.push(h('code', 'md-code', match[2]));
      else if (match[3] !== undefined) out.push(h('strong', null, inline(match[3])));
      else if (match[4] !== undefined) out.push(h('em', null, inline(match[4])));
      else if (match[5] !== undefined) out.push(h('del', null, inline(match[5])));
      else if (match[6] !== undefined) out.push(link(match[6], match[7]));
      else out.push(link(match[8], match[8]));
      last = match.index + match[0].length;
    }
    if (last < text.length) out.push(text.slice(last));
    return out;
  }

  /** Lines of one paragraph: a newline somebody typed is a line break here, as it was for them. */
  function lines(texts) {
    return texts.flatMap((text, index) => (index === 0 ? inline(text) : [h('br'), ...inline(text)]));
  }

  // ---- Markdown, blocks ---------------------------------------------------------------------

  const FENCE = /^\s{0,3}(```+|~~~+)\s*([\w+#.-]*)\s*$/;
  const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
  const RULE = /^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/;
  const ITEM = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/;
  const TABLE_RULE = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;
  const cells = (row) =>
    row
      .trim()
      .replace(/^\|/, '')
      .replace(/\|$/, '')
      .split(/(?<!\\)\|/)
      .map((cell) => cell.trim().replace(/\\\|/g, '|'));

  function markdown(text) {
    const out = document.createDocumentFragment();
    const source = text.replace(/\r\n?/g, '\n').split('\n');
    let i = 0;
    while (i < source.length) {
      const line = source[i];
      if (line.trim() === '') {
        i++;
        continue;
      }
      const fence = FENCE.exec(line);
      if (fence) {
        const body = [];
        i++;
        while (i < source.length && !(source[i].trim().startsWith(fence[1]) && source[i].trim().replace(/[`~]/g, '') === '')) {
          body.push(source[i++]);
        }
        i++;
        out.append(code(body.join('\n'), fence[2]));
        continue;
      }
      const heading = HEADING.exec(line);
      if (heading) {
        const node = h('div', `md-h md-h${heading[1].length}`, inline(heading[2]));
        node.setAttribute('role', 'heading');
        node.setAttribute('aria-level', String(heading[1].length + 2));
        out.append(node);
        i++;
        continue;
      }
      if (RULE.test(line)) {
        out.append(h('hr', 'md-rule'));
        i++;
        continue;
      }
      if (line.includes('|') && i + 1 < source.length && TABLE_RULE.test(source[i + 1]) && source[i + 1].includes('-')) {
        const head = cells(line);
        const rows = [];
        i += 2;
        while (i < source.length && source[i].includes('|') && source[i].trim() !== '') rows.push(cells(source[i++]));
        out.append(
          h(
            'div',
            'md-table',
            h(
              'table',
              null,
              h('thead', null, h('tr', null, head.map((cell) => h('th', null, inline(cell))))),
              h('tbody', null, rows.map((row) => h('tr', null, head.map((_, column) => h('td', null, inline(row[column] ?? '')))))),
            ),
          ),
        );
        continue;
      }
      if (/^\s{0,3}>/.test(line)) {
        const quoted = [];
        while (i < source.length && /^\s{0,3}>/.test(source[i])) quoted.push(source[i++].replace(/^\s{0,3}>\s?/, ''));
        out.append(h('blockquote', 'md-quote', markdown(quoted.join('\n'))));
        continue;
      }
      if (ITEM.test(line)) {
        i = list(source, i, out);
        continue;
      }
      const paragraph = [];
      while (
        i < source.length &&
        source[i].trim() !== '' &&
        !FENCE.test(source[i]) &&
        !HEADING.test(source[i]) &&
        !RULE.test(source[i]) &&
        !ITEM.test(source[i]) &&
        !/^\s{0,3}>/.test(source[i])
      ) {
        paragraph.push(source[i++]);
      }
      out.append(h('p', 'md-p', lines(paragraph)));
    }
    return out;
  }

  /** A list from line `start`; items indented under an item nest in it. Returns the line after. */
  function list(source, start, out) {
    const open = [];
    let i = start;
    while (i < source.length) {
      const item = ITEM.exec(source[i]);
      if (!item) {
        // A line indented under the last item continues it; anything else ends the list.
        if (source[i].trim() !== '' && /^\s+/.test(source[i]) && open.length) {
          open[open.length - 1].last.append(h('br'), ...inline(source[i].trim()));
          i++;
          continue;
        }
        break;
      }
      const indent = item[1].length;
      const ordered = /\d/.test(item[2]);
      while (open.length && indent < open[open.length - 1].indent) open.pop();
      if (!open.length || indent > open[open.length - 1].indent) {
        const node = h(ordered ? 'ol' : 'ul', 'md-list');
        if (ordered) node.start = parseInt(item[2], 10);
        if (open.length) open[open.length - 1].last.append(node);
        else out.append(node);
        open.push({ indent, node, last: null });
      }
      const level = open[open.length - 1];
      level.last = h('li', null, inline(item[3]));
      level.node.append(level.last);
      i++;
    }
    return i;
  }

  function code(text, language) {
    return h('div', 'md-fence', language ? h('span', 'md-lang', language) : null, h('pre', 'code', text));
  }

  // ---- what Claude Code wraps into a prompt ------------------------------------------------

  const WRAPPED = /<([a-zA-Z][\w-]*)((?:\s[^<>]*)?)>([\s\S]*?)<\/\1>/g;
  const SHELL = new Set(['bash-input', 'bash-stdout', 'bash-stderr', 'local-command-stdout', 'local-command-stderr']);

  /** A prompt as the pieces it is made of: `{ tag, attributes, text }`, tag null for what was typed. */
  function pieces(text) {
    const out = [];
    let last = 0;
    for (const match of text.matchAll(WRAPPED)) {
      const before = text.slice(last, match.index);
      if (before.trim()) out.push({ tag: null, text: before.trim() });
      out.push({ tag: match[1].toLowerCase(), attributes: match[2].trim(), text: match[3] });
      last = match.index + match[0].length;
    }
    const rest = text.slice(last);
    if (rest.trim() || out.length === 0) out.push({ tag: null, text: rest.trim() });
    return out;
  }

  const inner = (text, tag) => new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(text)?.[1].trim();

  /**
   * What a prompt is, in a line: the command it ran, the notification it carries, or what was typed.
   * `kind` says which, for the label and the colour of its row.
   */
  function gist(text) {
    const parts = pieces(text);
    const command = parts.find((part) => part.tag === 'command-name');
    if (command) {
      const args = parts.find((part) => part.tag === 'command-args')?.text.trim();
      return { kind: 'command', line: `${command.text.trim()}${args ? ` ${args}` : ''}` };
    }
    const notice = parts.find((part) => part.tag === 'task-notification');
    if (notice) return { kind: 'notice', line: inner(notice.text, 'summary') ?? inner(notice.text, 'status') ?? notice.text.trim() };
    const typed = parts.filter((part) => part.tag === null).map((part) => part.text);
    if (typed.join('').trim()) return { kind: 'typed', line: typed.join(' ') };
    const output = parts.every((part) => part.tag === null || SHELL.has(part.tag));
    return { kind: output ? 'output' : 'wrapped', line: parts.map((part) => part.text.trim()).join(' ') };
  }

  function block(className, label, ...children) {
    return h('div', `block ${className}`, h('div', 'block-label', label), ...children);
  }

  function prompt(text, labels) {
    const out = document.createDocumentFragment();
    const parts = pieces(text);
    const command = parts.find((part) => part.tag === 'command-name');
    for (const part of parts) {
      if (part.tag === null) {
        out.append(markdown(part.text));
      } else if (part.tag === 'command-name') {
        const args = parts.find((other) => other.tag === 'command-args')?.text.trim();
        out.append(block('command', labels.command, h('pre', 'code', `${part.text.trim()}${args ? ` ${args}` : ''}`)));
      } else if (command && (part.tag === 'command-message' || part.tag === 'command-args')) {
        // Said by the command block already.
      } else if (part.tag === 'task-notification') {
        const facts = [];
        let rest = part.text;
        for (const match of part.text.matchAll(WRAPPED)) {
          facts.push(h('dt', null, match[1]), h('dd', null, match[1] === 'summary' || match[1] === 'result' ? markdown(match[3].trim()) : match[3].trim()));
          rest = rest.replace(match[0], '');
        }
        out.append(block('notice', labels.notice, facts.length ? h('dl', 'facts', facts) : null, rest.trim() ? markdown(rest.trim()) : null));
      } else if (part.tag === 'system-reminder') {
        const details = h('details', 'block reminder', h('summary', 'block-label', labels.reminder), markdown(part.text.trim()));
        out.append(details);
      } else if (SHELL.has(part.tag)) {
        if (part.text.trim()) out.append(block('output', part.tag, h('pre', 'code', part.text.replace(/^\n+|\n+$/g, ''))));
      } else {
        out.append(block('wrapped', part.attributes ? `${part.tag} ${part.attributes}` : part.tag, markdown(part.text.trim())));
      }
    }
    return out;
  }

  // ---- tool inputs ----------------------------------------------------------------------------

  const JSON_TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;

  /** JSON laid out, with keys, strings, numbers and literals told apart. */
  function json(value) {
    const text = JSON.stringify(value, null, 2);
    const pre = h('pre', 'code json');
    let last = 0;
    for (const match of text.matchAll(JSON_TOKEN)) {
      if (match.index > last) pre.append(text.slice(last, match.index));
      if (match[1] && match[2]) pre.append(h('span', 'j-key', match[1]), match[2]);
      else if (match[1]) pre.append(h('span', 'j-string', match[1]));
      else if (match[3]) pre.append(h('span', 'j-literal', match[0]));
      else pre.append(h('span', 'j-number', match[0]));
      last = match.index + match[0].length;
    }
    if (last < text.length) pre.append(text.slice(last));
    return pre;
  }

  const isText = (value) => typeof value === 'string';

  /** A tool's input the way the tool is used: a command to run, a file and what changes in it. */
  function toolInput(toolName, text) {
    let input;
    try {
      input = JSON.parse(text);
    } catch {
      return h('pre', 'code', text);
    }
    if (input === null || typeof input !== 'object' || Array.isArray(input)) return json(input);
    const out = document.createDocumentFragment();
    const rest = { ...input };
    const take = (name) => {
      const value = rest[name];
      delete rest[name];
      return value;
    };
    if (toolName === 'Bash' && isText(input.command)) {
      const description = take('description');
      if (isText(description)) out.append(h('div', 'caption', description));
      out.append(h('pre', 'code shell', take('command')));
    } else if ((toolName === 'Edit' || toolName === 'Write' || toolName === 'Read' || toolName === 'NotebookEdit') && isText(input.file_path)) {
      out.append(h('div', 'caption path', take('file_path')));
      if (isText(input.old_string)) out.append(h('pre', 'code removed', take('old_string')));
      if (isText(input.new_string)) out.append(h('pre', 'code added', take('new_string')));
      if (isText(input.content)) out.append(h('pre', 'code added', take('content')));
    }
    if (Object.keys(rest).length > 0) out.append(json(rest));
    return out;
  }

  return { markdown, prompt, gist, toolInput, json };
})();
