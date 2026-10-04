alter table user_accounts add column role varchar(16) not null default 'PLAYER';
alter table user_accounts add column enabled boolean not null default true;
alter table user_accounts add column updated_at timestamp with time zone not null default current_timestamp;

alter table hand_records add column room_name varchar(64) not null default '旧牌局';

create table poker_rooms (
    id varchar(36) primary key,
    code varchar(6) not null unique,
    name varchar(64) not null,
    password_hash varchar(100),
    host_username varchar(32) not null,
    small_blind bigint not null,
    big_blind bigint not null,
    initial_stack bigint not null,
    max_players integer not null,
    action_seconds integer not null,
    status varchar(16) not null,
    state_json text not null,
    state_version bigint not null,
    action_deadline timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_rooms_host foreign key (host_username) references user_accounts(username)
);

create table room_members (
    room_id varchar(36) not null,
    username varchar(32) not null,
    joined_at timestamp with time zone not null,
    primary key (room_id, username),
    constraint fk_members_room foreign key (room_id) references poker_rooms(id),
    constraint fk_members_user foreign key (username) references user_accounts(username)
);

create table hand_record_players (
    hand_id varchar(36) not null,
    username varchar(32) not null,
    primary key (hand_id, username),
    constraint fk_hand_players_hand foreign key (hand_id) references hand_records(id) on delete cascade,
    constraint fk_hand_players_user foreign key (username) references user_accounts(username)
);

create index idx_rooms_status on poker_rooms(status, updated_at);
create index idx_room_members_user on room_members(username, joined_at);
create index idx_hand_players_user on hand_record_players(username, hand_id);
