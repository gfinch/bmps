#!/bin/bash
# Export aggregated order report to CSV
# Usage: ./run-export.sh [output-file] [accountId]
# Example: ./run-export.sh orders.csv
# Example: ./run-export.sh orders.csv MyAccount123

sbt "console/runMain bmps.console.AggregatedReportExporter $@"
