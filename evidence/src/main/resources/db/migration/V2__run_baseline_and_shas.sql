alter table run add column origin_sha      varchar(40);
alter table run add column last_kept_sha   varchar(40);
alter table run add column baseline_rps    numeric(10,2);
alter table run add column noise_floor_rps numeric(10,2);
