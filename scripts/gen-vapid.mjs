// Generate a VAPID key pair (P-256) for web push and append it to .env (never committed). Refuses to overwrite.
//   node scripts/gen-vapid.mjs
import { generateKeyPairSync } from 'node:crypto';
import { appendFileSync, existsSync, readFileSync } from 'node:fs';

const env = new URL('../.env', import.meta.url);
if (existsSync(env) && /^VAPID_PRIVATE_KEY=./m.test(readFileSync(env, 'utf8'))) {
  console.log('.env already has VAPID keys; delete them first to rotate (every browser must then subscribe again).');
  process.exit(0);
}
const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = privateKey.export({ format: 'jwk' });
const pub = publicKey.export({ format: 'der', type: 'spki' }).subarray(-65); // uncompressed point 04||x||y
appendFileSync(env, `\n# Web push (P5.B, scripts/gen-vapid.mjs)\nVAPID_PUBLIC_KEY=${pub.toString('base64url')}\nVAPID_PRIVATE_KEY=${jwk.d}\n`);
console.log('VAPID keys appended to .env');
