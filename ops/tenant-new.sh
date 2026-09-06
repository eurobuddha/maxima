#!/usr/bin/env bash
# Add one hosted account to a running Parlons multi-account host and print its invite.
#
#   ops/tenant-new.sh <ssh-target> <name> [--dir /var/lib/parlons-tenants]
#
# The invite is the account's permanent address plus a ONE-TIME pairing code, in one string
# (MAX#…?code=XXXX-XXXX-XXXX). Show it to the user as a QR (any QR maker) or send the text;
# the iPhone app scans or pastes it and pairs. Runs the operator command on the box as the
# unprivileged `maxima` user, so the folder has the right owner.
set -euo pipefail
TARGET="${1:-}"; NAME="${2:-}"; DIR=/var/lib/parlons-tenants
[ -n "$TARGET" ] && [ -n "$NAME" ] || { sed -n '2,9p' "$0" | sed 's/^#\ \?//'; exit 1; }
shift 2
while [ $# -gt 0 ]; do case "$1" in --dir) DIR="$2"; shift 2 ;; *) echo "unknown option: $1" >&2; exit 1 ;; esac; done
ssh -o ConnectTimeout=20 -o BatchMode=yes "$TARGET" \
    "runuser -u maxima -- java -jar /opt/maxima/parlons-cloud.jar --tenant-new $DIR '$NAME'"
