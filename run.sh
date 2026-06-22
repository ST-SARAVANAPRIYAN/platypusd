#!/bin/bash

# Cleanly stop both the daemon and Vite dev server on Ctrl+C
cleanup() {
    echo -e "\nStopping all services..."
    kill "$DAEMON_PID" 2>/dev/null
    kill "$VITE_PID" 2>/dev/null
    exit 0
}

trap cleanup SIGINT SIGTERM

echo "Starting platypusd daemon..."
./target/debug/platypusd-core &
DAEMON_PID=$!

echo "Starting desktop dashboard interface..."
npm run --prefix desktop dev &
VITE_PID=$!

# Wait for background jobs to finish
wait
