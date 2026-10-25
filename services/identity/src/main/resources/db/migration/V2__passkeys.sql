-- Passkeys (E003-E005, services.md §identity). public_key holds webauthn4j's attested credential data
-- (AAGUID, credential id, COSE public key); no private material ever reaches the server.
create table passkeys (
    id            uuid primary key,
    user_id       uuid not null references users(id),
    credential_id bytea not null unique,
    public_key    bytea not null,
    sign_count    bigint not null,
    transports    text[] not null default '{}',
    created_at    timestamptz not null default clock_timestamp(),
    last_used_at  timestamptz
);
create index passkeys_by_user on passkeys (user_id);

-- One-time WebAuthn challenges. user_id is null for sign-in (discoverable credentials: the user is not known yet).
create table passkey_challenges (
    id         uuid primary key,
    user_id    uuid references users(id),
    purpose    text not null check (purpose in ('REGISTER', 'AUTHENTICATE')),
    challenge  bytea not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    created_at timestamptz not null default clock_timestamp()
);
