#!/bin/bash
# Run the Tradovate Broker Test Console

cd "$(dirname "$0")"
sbt "project console" "run"
