#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_SERVER="kafka:29092"
PARTITIONS=3
REPLICATION_FACTOR=1

TOPICS=(
  "mugen.user.registered"
  "mugen.user.followed"
  "mugen.post.created"
  "mugen.post.liked"
  "mugen.video.transcode.jobs"
  "mugen.video.progress.events"
  "mugen.payment.completed"
)

for topic in "${TOPICS[@]}"; do
  echo "Creating topic: ${topic}"
  kafka-topics --create --if-not-exists \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --topic "${topic}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION_FACTOR}"
done

echo "All mugen.* topics created."
kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --list
