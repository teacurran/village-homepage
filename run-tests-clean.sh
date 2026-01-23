#!/bin/bash
# Temporarily rename .env and unset environment variables to allow Dev Services to work
mv .env .env.backup.tmp 2>/dev/null
unset QUARKUS_DATASOURCE_JDBC_URL
unset QUARKUS_DATASOURCE_USERNAME
unset QUARKUS_DATASOURCE_PASSWORD
unset QUARKUS_DATASOURCE_JDBC_MIN_SIZE
unset QUARKUS_DATASOURCE_JDBC_MAX_SIZE
mvn test "$@"
TEST_RESULT=$?
mv .env.backup.tmp .env 2>/dev/null
exit $TEST_RESULT
