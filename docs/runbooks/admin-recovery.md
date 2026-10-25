# Platform admin access and recovery (AMENDMENT-008, Q-043)

Platform admin powers need a **passkey** sign-in. An admin who signs in with an email code gets an ordinary session
without `platform_admin` (identity drops the role at sign-in and on every validation), so email alone can never
grant admin access.

## First passkey (enrolment)
1. Sign in with the email code (no admin powers yet).
2. Account page, "Add a passkey". Register at least **two** passkeys on different devices (e.g. phone and a
   security key), so losing one device is not a lockout.
3. Sign out, sign in with "Sign in with a passkey": the session now carries `platform_admin`.

## Lost passkeys (recovery)
Email OTP is **not** an admin recovery path. Recovery is a two-person procedure:
1. A **second platform admin** (signed in with a passkey) verifies the requester out of band (video call or in
   person, against the staff record) and opens a recovery ticket naming the account.
2. An operator with database access removes the lost credentials for that user only, inside a transaction, and
   records the ticket id:
   `delete from identity.passkeys where user_id = '<user-id>' and credential_id = '<lost-credential>';`
   then revokes the user's sessions: `update identity.sessions set revoked_at = clock_timestamp() where user_id = '<user-id>';`
3. The admin re-enrols as in "First passkey". Both people record the action in the ticket.
4. If no second admin exists (single-admin setup), the operator with database access acts alone and the owner
   signs off the ticket afterwards. Keep at least two admins to avoid this.

Local stack: `admin1@convoy.test` is a seeded platform admin; enrol a passkey through the browser's or Playwright's
virtual authenticator to use admin pages locally.
