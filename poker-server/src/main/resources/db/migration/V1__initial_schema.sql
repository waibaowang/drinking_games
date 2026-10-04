create table user_accounts (
    id varchar(36) primary key,
    username varchar(32) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(32) not null,
    created_at timestamp with time zone not null
);

create table hand_records (
    id varchar(36) primary key,
    room_id varchar(36) not null,
    hand_number bigint not null,
    summary text not null,
    completed_at timestamp with time zone not null,
    constraint uk_hand_room_number unique (room_id, hand_number)
);

create index idx_hand_records_room on hand_records(room_id, completed_at);

