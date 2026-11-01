-- P6.B public demo: temporary visitor accounts (one scenario = a buyer + the admin of a fresh demo dealership) and the
-- simulated dealers' staff users. Role 'demo' marks scenario accounts, so pool keeps their pools to themselves.
alter table user_roles drop constraint user_roles_role_check;
alter table user_roles add constraint user_roles_role_check check (role in ('buyer', 'dealer_staff', 'dealer_admin', 'platform_admin', 'demo'));
alter table sessions drop constraint sessions_auth_method_check;
alter table sessions add constraint sessions_auth_method_check check (auth_method in ('otp', 'passkey', 'demo'));

create table demo_users (
    user_id     uuid primary key references users(id),
    kind        text not null check (kind in ('SCENARIO', 'BOT')),
    scenario_id uuid,
    expires_at  timestamptz,
    erased      boolean not null default false
);
create index demo_users_due on demo_users(expires_at) where kind = 'SCENARIO' and not erased;
