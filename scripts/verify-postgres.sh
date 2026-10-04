#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
STAMP=$$
IMAGE="friend-poker-migration-check:$STAMP"

cleanup() {
  docker rm -f "poker-check-app-$STAMP" "poker-check-pg-$STAMP" >/dev/null 2>&1 || true
  docker network rm "poker-check-$STAMP" >/dev/null 2>&1 || true
  docker image rm "$IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

cd "$ROOT"
docker build -f poker-server/Dockerfile -t "$IMAGE" .

run_scenario() {
  mode=$1
  docker network create "poker-check-$STAMP" >/dev/null
  docker run -d --name "poker-check-pg-$STAMP" --network "poker-check-$STAMP" \
    -e POSTGRES_DB=poker -e POSTGRES_USER=poker -e POSTGRES_PASSWORD=check-only postgres:17-alpine >/dev/null
  attempts=0
  until docker exec "poker-check-pg-$STAMP" pg_isready -U poker -d poker >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -gt 60 ] || [ "$(docker inspect -f '{{.State.Status}}' "poker-check-pg-$STAMP")" = exited ]; then
      docker logs "poker-check-pg-$STAMP"
      exit 1
    fi
    sleep 1
  done

  baseline=""
  if [ "$mode" = upgrade ]; then
    docker exec -i "poker-check-pg-$STAMP" psql -v ON_ERROR_STOP=1 -U poker -d poker \
      < poker-server/src/main/resources/db/migration/V1__initial_schema.sql >/dev/null
    docker exec "poker-check-pg-$STAMP" psql -v ON_ERROR_STOP=1 -U poker -d poker -c \
      "insert into user_accounts values ('u1','old_user','hash','Old Player',current_timestamp); insert into hand_records values ('h1','room-1',1,'Old record',current_timestamp);" >/dev/null
    baseline="-e SPRING_FLYWAY_BASELINE_ON_MIGRATE=true -e SPRING_FLYWAY_BASELINE_VERSION=1"
  fi

  # shellcheck disable=SC2086
  docker run -d --name "poker-check-app-$STAMP" --network "poker-check-$STAMP" \
    -e DATABASE_URL=jdbc:postgresql://poker-check-pg-$STAMP:5432/poker \
    -e DATABASE_USERNAME=poker -e DATABASE_PASSWORD=check-only $baseline "$IMAGE" >/dev/null
  attempts=0
  until docker logs "poker-check-app-$STAMP" 2>&1 | grep -q "Started PokerApplication"; do
    attempts=$((attempts + 1))
    if [ "$attempts" -gt 60 ] || [ "$(docker inspect -f '{{.State.Status}}' "poker-check-app-$STAMP")" = exited ]; then
      docker logs "poker-check-app-$STAMP"
      exit 1
    fi
    sleep 1
  done
  version=$(docker exec "poker-check-pg-$STAMP" psql -At -U poker -d poker \
    -c 'select max(version) from flyway_schema_history where success')
  test "$version" = "3"
  if [ "$mode" = upgrade ]; then
    retained=$(docker exec "poker-check-pg-$STAMP" psql -At -U poker -d poker -c \
      "select role || ':' || enabled || ':' || (select room_name from hand_records where id='h1') from user_accounts where id='u1'")
    test "$retained" = "PLAYER:true:旧牌局"
  fi
  docker rm -f "poker-check-app-$STAMP" "poker-check-pg-$STAMP" >/dev/null
  docker network rm "poker-check-$STAMP" >/dev/null
}

run_scenario clean
run_scenario upgrade
echo "PostgreSQL 全新库与 V1 升级库迁移均通过。"
