-- P2 step 0.4: a parked event keeps its envelope, so SQS can delete the message and the queue keeps flowing.
alter table parked_events add column envelope text not null default '';
alter table parked_events alter column envelope drop default;
