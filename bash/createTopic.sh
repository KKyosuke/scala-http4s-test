#!/bin/bash
docker exec -it broker /bin/bash

# kafka-topics --bootstrap-server broker:9092 --create --topic sample-topic --partitions 3 replication-factor 1
# kafka-topics --bootstrap-server broker:9092 --describe --topic sample-topic