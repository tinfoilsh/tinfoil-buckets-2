#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="${ENDPOINT:-http://localhost:9000}"
BUCKET="${TEST_BUCKET:-anybucket}"
KEY="hello-$(date +%s).txt"
PAYLOAD="Hello, encrypted world! ($(date))"

echo "--- PUT $ENDPOINT/$BUCKET/$KEY ---"
curl -fsS -X PUT "$ENDPOINT/$BUCKET/$KEY" \
    -H "Content-Type: text/plain" \
    --data "$PAYLOAD" -i
echo

echo "--- HEAD $ENDPOINT/$BUCKET/$KEY ---"
curl -fsS -I "$ENDPOINT/$BUCKET/$KEY"
echo

echo "--- GET $ENDPOINT/$BUCKET/$KEY ---"
ROUND_TRIP=$(curl -fsS "$ENDPOINT/$BUCKET/$KEY")
echo "$ROUND_TRIP"

if [ "$ROUND_TRIP" = "$PAYLOAD" ]; then
    echo "PASS round-trip matches"
else
    echo "FAIL round-trip mismatch"
    exit 1
fi
echo

echo "--- DELETE $ENDPOINT/$BUCKET/$KEY ---"
curl -fsS -X DELETE "$ENDPOINT/$BUCKET/$KEY" -i
echo

echo "--- GET after DELETE (expect 404) ---"
curl -sS -o /dev/null -w "%{http_code}\n" "$ENDPOINT/$BUCKET/$KEY"
