create table flight_rooms (
    id varchar(36) primary key,
    code varchar(6) not null unique,
    name varchar(64) not null,
    password_hash varchar(100),
    host_username varchar(32) not null,
    status varchar(16) not null,
    state_json text not null,
    state_version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_flight_rooms_host foreign key (host_username) references user_accounts(username)
);

create table flight_room_members (
    room_id varchar(36) not null,
    username varchar(32) not null,
    joined_at timestamp with time zone not null,
    primary key (room_id, username),
    constraint fk_flight_members_room foreign key (room_id) references flight_rooms(id),
    constraint fk_flight_members_user foreign key (username) references user_accounts(username)
);

create index idx_flight_rooms_status on flight_rooms(status, updated_at);
create index idx_flight_members_user on flight_room_members(username, joined_at);

alter table hand_records add column game_type varchar(16) not null default 'POKER';
