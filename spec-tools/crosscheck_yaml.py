"""CI-only: verify miniyaml parses every spec YAML file exactly like PyYAML (needs pyyaml)."""
import datetime, pathlib, sys
import yaml
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import miniyaml

def norm(v):
    if isinstance(v, dict):
        return {str(k): norm(x) for k, x in v.items()}
    if isinstance(v, list):
        return [norm(x) for x in v]
    if isinstance(v, (datetime.date, datetime.datetime)):
        return v.isoformat()
    return v

root = pathlib.Path(__file__).resolve().parent.parent
bad = 0
for p in sorted(root.rglob('*.yaml')):
    if '.github' in p.parts:
        continue
    a = norm(yaml.safe_load(p.read_text(encoding='utf-8')))
    b = miniyaml.load(p)
    if a != b:
        bad += 1
        print('MISMATCH', p.relative_to(root))
print('crosscheck:', 'FAIL' if bad else 'ok')
sys.exit(1 if bad else 0)
