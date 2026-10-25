-- P2 MF-1: how many times a row was published successfully (a publish rolled back by a crash does not count).
alter table outbox add column attempts int not null default 0;
