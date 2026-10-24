-- identity schema (services.md §identity). Emails are stored as a keyed hash (lookup) + AES-GCM ciphertext.
create table users (
    id         uuid primary key,
    email_hash text not null unique,
    email_enc  bytea not null,
    locale     text not null default 'en',
    status     text not null default 'ACTIVE',
    created_at timestamptz not null default clock_timestamp()
);

create table user_roles (
    user_id uuid not null references users(id),
    role    text not null check (role in ('buyer', 'dealer_staff', 'dealer_admin', 'platform_admin')),
    primary key (user_id, role)
);

create table dealership_staff (
    dealership_id uuid not null,
    user_id       uuid not null references users(id),
    role          text not null check (role in ('dealer_staff', 'dealer_admin')),
    status        text not null default 'ACTIVE',
    invited_at    timestamptz not null default clock_timestamp(),
    revoked_at    timestamptz,
    primary key (dealership_id, user_id)
);

create table otp_challenges (
    id         uuid primary key,
    email_hash text not null,
    code_hash  text not null,
    expires_at timestamptz not null,
    attempts   int not null default 0,
    used_at    timestamptz,
    created_at timestamptz not null default clock_timestamp()
);
create index otp_by_email on otp_challenges (email_hash, created_at desc);

create table sessions (
    id_hash      text primary key,
    user_id      uuid not null references users(id),
    created_at   timestamptz not null default clock_timestamp(),
    last_seen_at timestamptz not null default clock_timestamp(),
    expires_at   timestamptz not null,
    revoked_at   timestamptz
);

create table service_clients (
    client_id   text primary key,
    secret_hash text not null,
    scopes      jsonb not null -- audience -> [scope]
);

create table signing_keys (
    kid         text primary key,
    public_jwk  text not null,
    private_enc bytea not null, -- AES-GCM with the data key (lite limitation: no KMS locally)
    status      text not null check (status in ('ACTIVE', 'RETIRED')),
    created_at  timestamptz not null default clock_timestamp()
);

-- Append-only consent history per purpose (PLAN §7).
create table consents (
    id             bigserial primary key,
    user_id        uuid not null references users(id),
    purpose        text not null,
    granted        boolean not null,
    notice_version text not null,
    at             timestamptz not null default clock_timestamp()
);
