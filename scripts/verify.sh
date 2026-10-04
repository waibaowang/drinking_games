#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
mvn verify
cd poker-web
pnpm install --frozen-lockfile
pnpm build
echo "全部验证通过。"
