"""Generate docs/architecture/events/*.schema.json (JSON Schema 2020-12) from event-catalogue.yaml.

python spec-tools/gen_event_schemas.py          # write
python spec-tools/gen_event_schemas.py --check  # exit 1 if stale
"""
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import miniyaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
ARCH = ROOT / 'docs' / 'architecture'
OUT = ARCH / 'events'
BASE = 'https://convoy.example/schemas/events/'
UUID = {'type': 'string', 'format': 'uuid'}
SCALARS = {
    'uuid': UUID,
    'string': {'type': 'string', 'minLength': 1},
    'int': {'type': 'integer'},
    'paise': {'type': 'integer', 'minimum': 0, 'description': 'Money in integer paise (ADR-012)'},
    'timestamp': {'type': 'string', 'format': 'date-time', 'description': 'UTC, RFC 3339'},
    'date': {'type': 'string', 'format': 'date', 'description': 'Calendar date in IST'},
    'bool': {'type': 'boolean'},
    'object': {'type': 'object'},
}


def field_schema(t):
    nullable = t.endswith('?')
    t = t.rstrip('?')
    array = t.endswith('[]')
    t = t[:-2] if array else t
    m = re.fullmatch(r'enum\((.+)\)', t)
    s = {'type': 'string', 'enum': m.group(1).split('|')} if m else dict(SCALARS[t])
    if array:
        s = {'type': 'array', 'items': s}
    if nullable:
        s = {'anyOf': [s, {'type': 'null'}]}
    return s


def envelope():
    return {
        '$schema': 'https://json-schema.org/draft/2020-12/schema', '$id': BASE + 'envelope.schema.json',
        'title': 'Convoy event envelope (PLAN §4.3)', 'type': 'object', 'additionalProperties': False,
        'required': ['event_id', 'event_type', 'schema_version', 'aggregate_id', 'aggregate_version', 'occurred_at',
                     'correlation_id', 'causation_id', 'producer', 'payload'],
        'properties': {
            'event_id': dict(UUID, description='UUIDv7; inbox dedupe key and SNS FIFO MessageDeduplicationId'),
            'event_type': {'type': 'string', 'pattern': '^[A-Z][A-Za-z]+$'},
            'schema_version': {'type': 'integer', 'minimum': 1},
            'aggregate_id': UUID,
            'aggregate_version': {'type': 'integer', 'minimum': 1, 'description': 'Owner aggregate version after the change; consumers detect gaps/stale events with it'},
            'occurred_at': {'type': 'string', 'format': 'date-time', 'description': 'DB commit-time clock of the owner (UTC)'},
            'correlation_id': UUID, 'causation_id': {'anyOf': [UUID, {'type': 'null'}]},
            'producer': {'type': 'string', 'enum': ['identity', 'catalog', 'pool', 'dealer', 'quote', 'order', 'payment']},
            'payload': {'type': 'object'},
        },
    }


def event_schema(e):
    payload = {'type': 'object', 'additionalProperties': False, 'required': sorted(e['payload']),
               'properties': {k: field_schema(v) for k, v in e['payload'].items()}}
    return {
        '$schema': 'https://json-schema.org/draft/2020-12/schema', '$id': BASE + f'{e["name"]}.schema.json',
        'title': f'{e["name"]} v1', 'description': f'Owner: {e["owner"]}. Consumers: {", ".join(e["consumers"])}. FIFO: {e["fifo"]}.',
        'allOf': [{'$ref': 'envelope.schema.json'}],
        'properties': {'event_type': {'const': e['name']}, 'schema_version': {'const': 1}, 'producer': {'const': e['owner']},
                       'payload': payload},
    }


def files():
    cat = miniyaml.load(ARCH / 'event-catalogue.yaml')
    out = {'envelope.schema.json': envelope()}
    for e in cat['events']:
        out[f'{e["name"]}.schema.json'] = event_schema(e)
    return out


if __name__ == '__main__':
    OUT.mkdir(exist_ok=True)
    want = {k: json.dumps(v, indent=2, ensure_ascii=False) + '\n' for k, v in files().items()}
    if '--check' in sys.argv:
        have = {p.name: p.read_text(encoding='utf-8') for p in OUT.glob('*.schema.json')}
        if have != want:
            sys.exit('event schemas stale: run spec-tools/gen_event_schemas.py')
        print('event schemas up to date')
    else:
        for p in OUT.glob('*.schema.json'):
            if p.name not in want:
                p.unlink()
        for k, v in want.items():
            (OUT / k).write_text(v, encoding='utf-8', newline='\n')
        print(f'wrote {len(want)} schemas')
