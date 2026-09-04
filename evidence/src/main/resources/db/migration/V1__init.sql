create table target
(
    id                    varchar(40) primary key,
    name                  varchar(256) not null,
    base_repo             varchar(500),
    seed_patch            text,
    baseline_sha          varchar(40),
    ground_truth_category varchar(10),
    ground_truth_fix      text,
    profile_hash          varchar(64)
);

create table run
(
    id                 varchar(30) primary key,
    target_id          varchar(40)            not null,
    status             varchar(12)            not null,
    started_at         pg_catalog.timestamptz not null,
    finished_at        pg_catalog.timestamptz,
    provider           varchar(100),
    model              varchar(100),
    prompt_hash        varchar(64),
    aggregator_version varchar(20),
    gen_params         pg_catalog.jsonb,
    tokens_in          bigint,
    tokens_out         bigint,
    cost_usd           numeric(10, 6),
    baseline_p95_ms    numeric(10, 2),
    noise_floor_ms     numeric(10, 2)
);

create table load_report
(
    id              bigserial primary key,
    run_id          varchar(30) not null,
    label           varchar(30) not null,
    payload         jsonb       not null,
    k6_summary_path text
);

create table jfr_report
(
    id         bigserial primary key,
    run_id     varchar(30)      not null,
    label      varchar(30)      not null,
    payload    pg_catalog.jsonb not null,
    jfr_path   text,
    jfr_sha256 varchar(64)
);

create table iteration
(
    id             bigserial primary key,
    run_id         varchar(30)            not null,
    n              int                    not null,
    hypothesis     jsonb,
    ledger         jsonb,
    change         jsonb,
    outcome        varchar(12),
    tree_sha       varchar(40),
    load_report_id bigint,
    jfr_report_id  bigint,
    files_touched  jsonb,
    keep_type      varchar(12),
    finding        text,
    created_at     pg_catalog.timestamptz not null default now()
);

create table trajectory_event
(
    id         bigserial primary key,
    run_id     varchar(30)            not null,
    ts         pg_catalog.timestamptz not null default now(),
    kind       varchar(20)            not null,
    payload    pg_catalog.jsonb,
    tokens_in  int,
    tokens_out int,
    cost_usd   numeric(10, 6)

);

