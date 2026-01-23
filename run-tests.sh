#!/bin/bash
# Wrapper script to run tests without development database environment variables
# This ensures test profiles (PostgreSQLTestProfile) control database configuration

# Temporarily rename .env to .env.backup to prevent Quarkus from loading it during tests
if [ -f .env ]; then
  mv .env .env.backup
  trap "mv .env.backup .env" EXIT INT TERM
fi

# Use env -i to run Maven in a clean environment, then add back essential variables
env -i \
  HOME="$HOME" \
  PATH="$PATH" \
  JAVA_HOME="$JAVA_HOME" \
  M2_HOME="$M2_HOME" \
  USER="$USER" \
  LANG="$LANG" \
  TERM="$TERM" \
  mvn "$@"
