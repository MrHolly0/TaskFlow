#!/bin/bash
set -e
cd "$(dirname "$0")"
URL=$(curl -s http://localhost:4040/api/tunnels | python3 -c "import json,sys; print(json.load(sys.stdin)['tunnels'][0]['public_url'])")
[ -z "$URL" ] && { echo "ngrok не запущен"; exit 1; }
if grep -q "^PUBLIC_BASE_URL=" .env; then
  sed -i '' "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=$URL|" .env
else
  echo "PUBLIC_BASE_URL=$URL" >> .env
fi
echo "PUBLIC_BASE_URL = $URL"
docker compose up -d --force-recreate core-service
