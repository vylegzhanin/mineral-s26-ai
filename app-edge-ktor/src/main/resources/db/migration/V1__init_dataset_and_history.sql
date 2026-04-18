create table if not exists dataset_object (
    id uuid primary key,
    dataset_id text not null,
    name text not null,
    category text,
    preview_ref text not null,
    properties jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

create table if not exists action_log (
    id bigserial primary key,
    scope_type text not null check (scope_type in ('USER', 'SESSION')),
    scope_id text not null,
    action_type text not null,
    payload jsonb not null,
    inverse_payload jsonb not null,
    created_at timestamptz not null default now()
);

create table if not exists history_cursor (
    scope_type text not null check (scope_type in ('USER', 'SESSION')),
    scope_id text not null,
    cursor_action_id bigint,
    updated_at timestamptz not null default now(),
    primary key (scope_type, scope_id)
);

create index if not exists idx_action_log_scope on action_log(scope_type, scope_id, id);
create index if not exists idx_dataset_object_dataset on dataset_object(dataset_id);
