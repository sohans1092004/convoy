-- AMENDMENT-008 (Q-043): how a session was opened; platform_admin is granted only to passkey sessions.
alter table sessions add column auth_method text not null default 'otp' check (auth_method in ('otp', 'passkey'));
