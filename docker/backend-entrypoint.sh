#!/bin/sh
set -eu

export DATABASE_PASSWORD="$(cat /run/dev-secrets/database-password)"
export JWT_SECRET_BASE64="$(cat /run/dev-secrets/jwt-secret)"

exec mvn --batch-mode --quiet -Dmaven.test.skip=true spring-boot:run
