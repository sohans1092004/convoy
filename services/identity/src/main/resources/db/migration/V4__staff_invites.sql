-- P3.A staff invites (services.md: staff_invites). Only the token's SHA-256 is stored; one use; expires by the DB clock.
create table staff_invites (
    id            uuid primary key,
    dealership_id uuid not null,
    email_hash    text not null,
    role          text not null check (role in ('dealer_staff', 'dealer_admin')),
    token_hash    text not null unique,
    invited_by    uuid not null,
    expires_at    timestamptz not null,
    accepted_at   timestamptz,
    accepted_by   uuid,
    created_at    timestamptz not null default clock_timestamp()
);
