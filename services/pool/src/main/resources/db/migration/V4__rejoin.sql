-- AMENDMENT-008 (Q-045): a buyer may rejoin after leaving, at most after 2 leaves per pool.
alter table memberships add column leave_count int not null default 0 check (leave_count >= 0);
update memberships set leave_count = 1 where state = 'LEFT';
