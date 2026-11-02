#!/usr/bin/env bash
# Exercises every tool against a running backend.
# Usage: BASE_URL=http://localhost:8787 SECRET=yoursecret ./scripts/test_interpret.sh
set -u
BASE_URL="${BASE_URL:-http://localhost:8787}"
SECRET="${SECRET:-${APP_SHARED_SECRET:-}}"

echo "== GET /health"
curl -s "$BASE_URL/health"; echo

phrases=(
  "Jarvis call king"
  "Jarvis message junior on WhatsApp hi"
  "text mom on my way"
  "set an alarm for 6:30 pm"
  "set a timer for 5 minutes"
  "open youtube"
  "search for best biryani near me"
  "play arijit singh"
  "what is the capital of France"
  "message"
)
for p in "${phrases[@]}"; do
  echo "== $p"
  curl -s -X POST "$BASE_URL/interpret" \
    -H "Content-Type: application/json" \
    -H "X-Jarvis-Secret: $SECRET" \
    -d "{\"text\": \"$p\", \"contacts\": [\"King\", \"Junior\", \"Mom\"], \"now\": \"$(date '+%Y-%m-%d %H:%M %a')\"}"
  echo
done

echo "== wrong secret (expect 401)"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE_URL/interpret" -H "Content-Type: application/json" -H "X-Jarvis-Secret: wrong" -d '{"text":"hi"}'
echo "== empty text (expect 400)"
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE_URL/interpret" -H "Content-Type: application/json" -H "X-Jarvis-Secret: $SECRET" -d '{"text":""}'
