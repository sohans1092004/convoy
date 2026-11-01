# P7 load-test accounts, full profile only (infra/aws/full: task "loadseed", run when LOAD_USERS > 0). No endpoint and no
# test-only code in the services: this one-off task writes buyers, dealer staff and their sessions straight into the
# identity schema exactly as sign-in does (users: HMAC-SHA256 email hash + AES-256-GCM email under the identity data
# key; user_roles; dealership_staff; a UserRegistered outbox event; sessions: SHA-256 of a random 256-bit token, 4 h
# expiry), then uploads the session ids to the run's prefix in the encrypted state bucket for the k6 generator.
# The run's database and that object are destroyed with the run; nothing of this exists in lite or local.
import base64, hashlib, hmac, json, os, secrets, time, uuid

import boto3
import psycopg
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

RUN = os.environ['RUN_ID']
BUYERS = int(os.environ['LOAD_USERS'])
DEALERSHIPS = [d for d in os.environ.get('LOAD_DEALERSHIPS', '').split(',') if d]
STAFF_PER = int(os.environ.get('LOAD_STAFF_PER_DEALERSHIP', '1'))
KEY = base64.b64decode(os.environ['IDENTITY_DATA_KEY'])
HOURS = 4


def uuid7():  # same ordering as the services' Uuid7: 48-bit ms timestamp, version 7, variant 10
    b = bytearray(int(time.time() * 1000).to_bytes(6, 'big') + secrets.token_bytes(10))
    b[6] = (b[6] & 0x0F) | 0x70
    b[8] = (b[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(b))


def email_hash(e): return hmac.new(KEY, e.encode(), hashlib.sha256).hexdigest()
def email_enc(e):
    iv = secrets.token_bytes(12)
    return iv + AESGCM(KEY).encrypt(iv, e.encode(), None)  # ciphertext || 16-byte tag, as javax AES/GCM/NoPadding
def token(): return base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode()


accounts = [('buyer', None, i) for i in range(BUYERS)] + [('dealer_staff', d, j) for d in DEALERSHIPS for j in range(STAFF_PER)]
out = []
with psycopg.connect(host=os.environ['PGHOST'], dbname='convoy', user='identity', password=os.environ['PGPASSWORD'],
                     sslmode=os.environ.get('PGSSLMODE', 'require'), options='-c search_path=identity') as db:
    with db.cursor() as c:
        for role, dealership, i in accounts:
            email = f'load+{RUN}-{role}-{dealership or "b"}-{i}@load.invalid'  # .invalid: notification never sends to it
            uid, tok = uuid7(), token()
            c.execute('insert into users(id, email_hash, email_enc) values (%s, %s, %s)', (uid, email_hash(email), email_enc(email)))
            c.execute('insert into user_roles(user_id, role) values (%s, %s)', (uid, role))
            if dealership:
                c.execute('insert into dealership_staff(dealership_id, user_id, role) values (%s, %s, %s)', (dealership, uid, role))
            c.execute("insert into outbox(event_id, event_type, aggregate_id, aggregate_version, payload, occurred_at, correlation_id) "
                      "values (%s, 'UserRegistered', %s, 1, %s::jsonb, clock_timestamp(), %s)",
                      (uuid7(), uid, json.dumps({'user_id': str(uid), 'roles': [role], 'locale': 'en'}), uuid.uuid4()))
            c.execute("insert into sessions(id_hash, user_id, expires_at, auth_method) values (%s, %s, clock_timestamp() + make_interval(hours => %s), 'otp')",
                      (hashlib.sha256(tok.encode()).hexdigest(), uid, HOURS))
            out.append({'session': tok, 'user_id': str(uid), 'role': role, 'dealership_id': dealership})
    db.commit()

if os.environ['STATE_BUCKET'] == '-':  # local test (tools/load/provision_test.sh): print instead of uploading
    open(os.environ['LOAD_OUT'], 'w').write(json.dumps(out)); raise SystemExit(0)
boto3.client('s3').put_object(Bucket=os.environ['STATE_BUCKET'], Key=f'full/{RUN}/load-sessions.json', Body=json.dumps(out).encode(),
                              ServerSideEncryption='AES256', ContentType='application/json')
print(f'provisioned {BUYERS} buyers and {len(accounts) - BUYERS} dealer staff for run {RUN}; sessions expire in {HOURS} h; '
      f's3://{os.environ["STATE_BUCKET"]}/full/{RUN}/load-sessions.json')
