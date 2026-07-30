#!/usr/bin/env bash
set -euo pipefail

# Reached over the INTERNAL listener — this runs as a container on mugen-network.
BOOTSTRAP_SERVER="kafka:29092"
PARTITIONS=3
REPLICATION_FACTOR=1

# The official apache/kafka image ships the CLI under /opt/kafka/bin with .sh
# suffixes, unlike the confluentinc images which put bare names on PATH.
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

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
  "${KAFKA_TOPICS}" --create --if-not-exists \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --topic "${topic}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION_FACTOR}"
done

echo "All mugen.* topics created."
"${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP_SERVER}" --list
