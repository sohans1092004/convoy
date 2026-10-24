"""Minimal YAML-subset loader (stdlib only).

Supported subset (all spec YAML files must stay inside it):
  - block mappings and block sequences, indentation by spaces
  - "- key: value" sequence items that open a mapping
  - scalars: null/~, true/false, integers, "double" (JSON escapes), 'single', plain strings
  - flow collections: [a, "b", 3], {k: v, j: [1, 2]}
  - comments starting with '#' outside quotes
Not supported: anchors, tags, block scalars (| >), multi-docs.
Floats are rejected on purpose: money and rates are integers (paise, basis points).
CI cross-checks this loader against PyYAML (see .github/workflows/spec.yml).
"""
import json
import re

_KEY = re.compile(r'^("(?:[^"\\]|\\.)*"|\'(?:[^\']|\'\')*\'|[^\s"\'\[\]{}#&*!|>%@`-][^#]*?|-[^\s#][^#]*?)\s*:(?:\s+|$)')
_INT = re.compile(r'^-?(0|[1-9][0-9]*)$')
_FLOAT = re.compile(r'^-?[0-9]+\.[0-9]+([eE][-+]?[0-9]+)?$')


class YamlError(ValueError):
    pass


def _strip_comment(s):
    q = None
    for i, c in enumerate(s):
        if q:
            if c == '\\' and q == '"':
                continue
            if c == q:
                q = None
        elif c in '"\'' and (i == 0 or s[i - 1] in ' [,:-'):
            q = c
        elif c == '#' and (i == 0 or s[i - 1] in ' \t'):
            return s[:i].rstrip()
    return s.rstrip()


def _split_flow(s):
    items, cur, q, depth = [], '', None, 0
    for c in s:
        if q:
            cur += c
            if c == q:
                q = None
        elif c in '"\'':
            q = c
            cur += c
        elif c == ',' and depth == 0:
            items.append(cur.strip())
            cur = ''
        else:
            depth += (c in '[{') - (c in ']}')
            cur += c
    if cur.strip():
        items.append(cur.strip())
    return items


def scalar(s, where=''):
    s = s.strip()
    if s in ('', '~', 'null'):
        return None
    if s == 'true':
        return True
    if s == 'false':
        return False
    if s.startswith('{'):
        if not s.endswith('}'):
            raise YamlError(f'{where}: unterminated flow mapping')
        out = {}
        for item in _split_flow(s[1:-1]):
            m = _KEY.match(item)
            if not m:
                raise YamlError(f'{where}: bad flow mapping entry: {item}')
            k = m.group(1)
            k = scalar(k, where) if k[0] in '"\'' else k
            if k in out:
                raise YamlError(f'{where}: duplicate key {k}')
            out[k] = scalar(item[m.end():], where)
        return out
    if s.startswith('['):
        if not s.endswith(']'):
            raise YamlError(f'{where}: unterminated flow sequence')
        return [scalar(x, where) for x in _split_flow(s[1:-1])]
    if s.startswith('"'):
        return json.loads(s)
    if s.startswith("'"):
        if not s.endswith("'"):
            raise YamlError(f'{where}: unterminated string')
        return s[1:-1].replace("''", "'")
    if _INT.match(s):
        return int(s)
    if _FLOAT.match(s):
        raise YamlError(f'{where}: floats are not allowed ({s}); use integers (paise / basis points) or quote it')
    if s.startswith(('&', '*', '!', '|', '>')):
        raise YamlError(f'{where}: unsupported YAML feature: {s}')
    if ': ' in s or s.endswith(':'):
        raise YamlError(f'{where}: plain scalar contains ": " - quote it: {s}')
    return s


def _is_item(t):
    return t == '-' or t.startswith('- ')


def loads(text, name='<yaml>'):
    lines = []
    for n, raw in enumerate(text.splitlines(), 1):
        if '\t' in raw[:len(raw) - len(raw.lstrip())]:
            raise YamlError(f'{name}:{n}: tab indentation')
        t = _strip_comment(raw)
        if t.strip() in ('', '---'):
            continue
        lines.append([len(t) - len(t.lstrip(' ')), t.strip(), n])
    if not lines:
        return None

    def block(i):
        return seq(i, lines[i][0]) if _is_item(lines[i][1]) else mapping(i, lines[i][0])

    def seq(i, ind):
        out = []
        while i < len(lines) and lines[i][0] == ind and _is_item(lines[i][1]):
            text, n = lines[i][1], lines[i][2]
            rest = text[1:].lstrip()
            if rest == '':
                i += 1
                if i >= len(lines) or lines[i][0] <= ind:
                    out.append(None)
                    continue
                v, i = block(i)
            elif _KEY.match(rest):
                lines[i] = [ind + len(text) - len(rest), rest, n]
                v, i = mapping(i, lines[i][0])
            else:
                v = scalar(rest, f'{name}:{n}')
                i += 1
            out.append(v)
        return out, i

    def mapping(i, ind):
        out = {}
        while i < len(lines) and lines[i][0] == ind and not _is_item(lines[i][1]):
            text, n = lines[i][1], lines[i][2]
            m = _KEY.match(text)
            if not m:
                raise YamlError(f'{name}:{n}: expected "key: value", got: {text}')
            k = m.group(1)
            k = scalar(k, f'{name}:{n}') if k[0] in '"\'' else k
            if k in out:
                raise YamlError(f'{name}:{n}: duplicate key {k}')
            rest = text[m.end():]
            i += 1
            if rest.strip() == '':
                if i < len(lines) and (lines[i][0] > ind or (lines[i][0] == ind and _is_item(lines[i][1]))):
                    v, i = block(i)
                else:
                    v = None
            else:
                v = scalar(rest, f'{name}:{n}')
            out[k] = v
        if i < len(lines) and lines[i][0] > ind:
            raise YamlError(f'{name}:{lines[i][2]}: unexpected indentation')
        return out, i

    v, i = block(0)
    if i != len(lines):
        raise YamlError(f'{name}:{lines[i][2]}: unexpected content at indentation {lines[i][0]}')
    return v


def load(path):
    with open(path, encoding='utf-8') as f:
        return loads(f.read(), str(path))


if __name__ == '__main__':
    doc = loads('''
a: 1
b:
  - x
  - k: "v: 1"   # comment
    j: [1, 'two', null]
c:
- 2
d: plain text here
e: ~
f: '2026-10-24'
g: {status: 409, title: "a, b (c)"}
''')
    assert doc == {'a': 1, 'b': ['x', {'k': 'v: 1', 'j': [1, 'two', None]}], 'c': [2],
                   'd': 'plain text here', 'e': None, 'f': '2026-10-24',
                   'g': {'status': 409, 'title': 'a, b (c)'}}, doc
    try:
        loads('x: 1.5')
        raise AssertionError('float accepted')
    except YamlError:
        pass
    print('miniyaml ok')
