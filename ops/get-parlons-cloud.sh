#!/usr/bin/env bash
#
# Parlons Cloud - one-command install of your own always-on Parlons account.
# Mac, Linux, Raspberry Pi, any VPS. Needs nothing but a shell; installs Java if missing.
#
#   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.sh | bash
#
#   … | bash -s -- --relay        public VPS: also run a relay for others on port 9501
#   … | bash -s -- --uninstall    stop and remove the service (your account data is kept)
#   … | bash -s -- --update       fetch the newest release and restart (re-running does this too)
#
# What it does:
#   1. Java 11+ (brew / apt / dnf / apk / pacman) and qrencode when a package manager exists
#   2. downloads the newest parlons-cloud release from GitHub and checks its sha256
#   3. installs a service that survives reboots: launchd (Mac), systemd (Linux)
#   4. starts it, pairs this computer's terminal as the first device, mints a fresh code
#   5. prints your address, the invite for your phone (as text and as a QR), and the `parlons` command
#
# It never prints your seed phrase. That lives in ~/.parlons/seed.txt - back it up like money.
set -euo pipefail

REPO="eurobuddha/maxima"
DATA="${PARLONS_DATA:-$HOME/.parlons}"
BIN="$DATA/bin"
CLIENT="$HOME/.parlons-client"
HEAP="${PARLONS_HEAP:-256m}"
MODE="account"      # or "relay"
ACTION="install"
for a in "$@"; do
    case "$a" in
        --relay) MODE="relay" ;;
        --uninstall) ACTION="uninstall" ;;
        --update) ACTION="install" ;;
        -h|--help) sed -n '2,19p' "$0" | sed 's/^#\ \?//'; exit 0 ;;
        *) echo "unknown option: $a" >&2; exit 2 ;;
    esac
done

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

OS="$(uname -s)"; ARCH="$(uname -m)"
case "$OS" in Darwin) OSN="mac" ;; Linux) OSN="linux" ;; *) die "Unsupported OS: $OS (Mac, Linux, Raspberry Pi and VPSes are supported)";; esac
SUDO=""
if [ "$OSN" = linux ] && [ "$(id -u)" != 0 ]; then
    if command -v sudo >/dev/null 2>&1; then SUDO="sudo"; else SUDO="__none__"; fi
fi
need_root() {   # a step that needs root on Linux: explain instead of failing on a lock file
    [ "$SUDO" != "__none__" ] || die "This step needs root and there is no sudo here. Either run this installer as root (it will then install for you), or install Java 11+ first (e.g. apt-get install default-jre-headless qrencode) and re-run as yourself."
}

# ---------- service helpers ----------
PLIST="$HOME/Library/LaunchAgents/com.eurobuddha.parlons-cloud.plist"
UNIT_USER="$HOME/.config/systemd/user/parlons-cloud.service"
UNIT_SYS="/etc/systemd/system/parlons-cloud.service"
have_systemd() { command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; }
user_systemd_ok() { have_systemd && systemctl --user show-environment >/dev/null 2>&1; }

svc_stop() {
    if [ "$OSN" = mac ]; then
        launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true
    elif user_systemd_ok && [ -f "$UNIT_USER" ]; then
        systemctl --user stop parlons-cloud >/dev/null 2>&1 || true
    elif have_systemd && [ -f "$UNIT_SYS" ]; then
        $SUDO systemctl stop parlons-cloud >/dev/null 2>&1 || true
    else
        [ -f "$DATA/cloud.pid" ] && kill "$(cat "$DATA/cloud.pid")" >/dev/null 2>&1 || true
    fi
}

if [ "$ACTION" = uninstall ]; then
    say "Removing the Parlons Cloud service (your account data in $DATA stays)"
    svc_stop
    rm -f "$PLIST" "$UNIT_USER"
    if have_systemd && [ -f "$UNIT_SYS" ]; then $SUDO rm -f "$UNIT_SYS"; $SUDO systemctl daemon-reload || true; fi
    user_systemd_ok && systemctl --user daemon-reload >/dev/null 2>&1 || true
    rm -rf "$BIN"
    note "Done. To remove the account itself (identity, chats, seed): rm -rf $DATA $CLIENT"
    exit 0
fi

# ---------- 1. java + qrencode ----------
say "[1/5] Java"
java_ok() { command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1 | grep -qE '"(1[1-9]|[2-9][0-9])'; }
if java_ok; then
    note "present: $(java -version 2>&1 | head -1)"
else
    note "installing Java (needs 11 or newer)"
    if [ "$OSN" = mac ]; then
        command -v brew >/dev/null 2>&1 || die "Install Homebrew first (https://brew.sh), or Java 21 from https://adoptium.net, then re-run."
        brew install --quiet openjdk@21 >/dev/null
        # brew's openjdk is keg-only: put it on PATH for this run and for the service
        JHOME="$(brew --prefix openjdk@21)"; export PATH="$JHOME/bin:$PATH"
    elif command -v apt-get >/dev/null 2>&1; then
        need_root; $SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -qq && $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq default-jre-headless qrencode >/dev/null 2>&1
    elif command -v dnf >/dev/null 2>&1; then
        need_root
        $SUDO dnf install -y -q java-21-openjdk-headless qrencode >/dev/null
    elif command -v apk >/dev/null 2>&1; then
        need_root; $SUDO apk add --quiet openjdk21-jre-headless libqrencode-tools
    elif command -v pacman >/dev/null 2>&1; then
        need_root; $SUDO pacman -S --noconfirm --quiet jre-openjdk-headless qrencode >/dev/null
    else
        die "No package manager found. Install Java 11+ (https://adoptium.net) and re-run."
    fi
    java_ok || die "Java did not install cleanly; install Java 11+ from https://adoptium.net and re-run."
    note "installed: $(java -version 2>&1 | head -1)"
fi
if ! command -v qrencode >/dev/null 2>&1; then
    if [ "$OSN" = mac ] && command -v brew >/dev/null 2>&1; then brew install --quiet qrencode >/dev/null 2>&1 || true
    elif command -v apt-get >/dev/null 2>&1 && [ "$SUDO" != "__none__" ]; then $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qrencode >/dev/null 2>&1 || true
    fi
fi
JAVA_BIN="$(command -v java)"

# ---------- 2. download ----------
say "[2/5] Newest Parlons Cloud release"
mkdir -p "$BIN" "$DATA/log"
API="https://api.github.com/repos/$REPO/releases?per_page=50"
JSON="$(curl -fsSL -H 'Accept: application/vnd.github+json' "$API")" || die "Could not reach GitHub to find the release."
JAR_URL="$(printf '%s' "$JSON" | grep -oE 'https://github.com/[^"]+/cloud-v[^"]+/parlons-cloud-[0-9.]+\.jar' | head -1)"
SUM_URL="$(printf '%s' "$JSON" | grep -oE 'https://github.com/[^"]+/cloud-v[^"]+/SHA256SUMS' | head -1)"
[ -n "$JAR_URL" ] || die "No parlons-cloud release found under $REPO."
JAR_NAME="$(basename "$JAR_URL")"; VER="${JAR_NAME#parlons-cloud-}"; VER="${VER%.jar}"
if [ -f "$BIN/$JAR_NAME" ]; then
    note "already have $VER"
else
    note "downloading $JAR_NAME"
    curl -fsSL -o "$BIN/$JAR_NAME.part" "$JAR_URL"
    if [ -n "$SUM_URL" ]; then
        want="$(curl -fsSL "$SUM_URL" | grep " $JAR_NAME\$" | cut -d' ' -f1 || true)"
        if command -v shasum >/dev/null 2>&1; then got="$(shasum -a 256 "$BIN/$JAR_NAME.part" | cut -d' ' -f1)"; else got="$(sha256sum "$BIN/$JAR_NAME.part" | cut -d' ' -f1)"; fi
        [ -z "$want" ] || [ "$want" = "$got" ] || { rm -f "$BIN/$JAR_NAME.part"; die "Checksum mismatch for $JAR_NAME - download refused."; }
        note "sha256 verified"
    fi
    mv "$BIN/$JAR_NAME.part" "$BIN/$JAR_NAME"
fi
ln -sf "$JAR_NAME" "$BIN/parlons-cloud.jar"

# ---------- the parlons command ----------
cat > "$BIN/parlons" <<EOF
#!/usr/bin/env bash
# parlons - drive your Parlons Cloud account from this computer (installed by get-parlons-cloud.sh)
DATA="$DATA"; BIN="$BIN"; CLIENT="$CLIENT"; JAVA="$JAVA_BIN"
export PARLONS_ACCOUNT_DIR="\$DATA"
case "\${1:-}" in
    start)   $( if [ "$OSN" = mac ]; then echo "launchctl bootstrap gui/\$(id -u) \"$PLIST\" 2>/dev/null || launchctl kickstart -k \"gui/\$(id -u)/com.eurobuddha.parlons-cloud\""; else echo "if [ -f \"$UNIT_USER\" ]; then systemctl --user start parlons-cloud; elif [ -f \"$UNIT_SYS\" ]; then ${SUDO:-} systemctl start parlons-cloud; else nohup \"\$JAVA\" -Xmx$HEAP -jar \"\$BIN/parlons-cloud.jar\" --data \"\$DATA\" $( [ "$MODE" = relay ] && echo "--relay-port 9501 --no-direct" || echo "--no-relay --no-direct" ) >> \"\$DATA/log/cloud.log\" 2>&1 & echo \$! > \"\$DATA/cloud.pid\"; fi"; fi ); exit \$? ;;
    stop)    $( if [ "$OSN" = mac ]; then echo "launchctl bootout \"gui/\$(id -u)\" \"$PLIST\""; else echo "if [ -f \"$UNIT_USER\" ]; then systemctl --user stop parlons-cloud; elif [ -f \"$UNIT_SYS\" ]; then ${SUDO:-} systemctl stop parlons-cloud; else kill \"\$(cat \"\$DATA/cloud.pid\" 2>/dev/null)\" 2>/dev/null; fi"; fi ); exit \$? ;;
    restart) "\$0" stop >/dev/null 2>&1; sleep 1; exec "\$0" start ;;
    log)     exec tail -n "\${2:-50}" -f "\$DATA/log/cloud.log" ;;
    update)  exec bash -c "curl -fsSL https://raw.githubusercontent.com/$REPO/main/ops/get-parlons-cloud.sh | bash -s -- --update $( [ "$MODE" = relay ] && echo --relay )" ;;
    uninstall) exec bash -c "curl -fsSL https://raw.githubusercontent.com/$REPO/main/ops/get-parlons-cloud.sh | bash -s -- --uninstall" ;;
    ""|-h|--help)
        "\$JAVA" -cp "\$BIN/parlons-cloud.jar" com.eurobuddha.maxima.cloud.Client
        echo; echo "  start | stop | restart | log [n] | update | uninstall   (this computer's service)"; exit 0 ;;
esac
exec "\$JAVA" -cp "\$BIN/parlons-cloud.jar" com.eurobuddha.maxima.cloud.Client --data "\$CLIENT" --name "\$(hostname -s 2>/dev/null || echo terminal)" "\$@"
EOF
chmod +x "$BIN/parlons"

# ---------- 3. service ----------
say "[3/5] Service (starts at login and after reboots)"
if [ "$MODE" = relay ]; then ARGS="--data $DATA --relay-port 9501 --no-direct"; else ARGS="--data $DATA --no-relay --no-direct"; fi
svc_stop
if [ "$OSN" = mac ]; then
    mkdir -p "$(dirname "$PLIST")"
    cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.eurobuddha.parlons-cloud</string>
  <key>ProgramArguments</key><array>
    <string>$JAVA_BIN</string><string>-Xmx$HEAP</string><string>-jar</string><string>$BIN/parlons-cloud.jar</string>
$(for a in $ARGS; do echo "    <string>$a</string>"; done)
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$DATA/log/cloud.log</string>
  <key>StandardErrorPath</key><string>$DATA/log/cloud.log</string>
</dict></plist>
EOF
    launchctl bootstrap "gui/$(id -u)" "$PLIST"
    note "launchd agent: $PLIST"
elif user_systemd_ok; then
    mkdir -p "$(dirname "$UNIT_USER")"
    cat > "$UNIT_USER" <<EOF
[Unit]
Description=Parlons Cloud - my always-on Parlons account
After=network-online.target

[Service]
ExecStart=$JAVA_BIN -Xmx$HEAP -jar $BIN/parlons-cloud.jar $ARGS
Restart=on-failure
RestartSec=10
StandardOutput=append:$DATA/log/cloud.log
StandardError=append:$DATA/log/cloud.log

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable --now parlons-cloud >/dev/null
    if command -v loginctl >/dev/null 2>&1 && [ "$SUDO" != "__none__" ]; then $SUDO loginctl enable-linger "$USER" >/dev/null 2>&1 || true; fi
    note "systemd user unit: $UNIT_USER (linger enabled so it runs when you are logged out)"
elif have_systemd && [ "$SUDO" != "__none__" ]; then
    $SUDO tee "$UNIT_SYS" >/dev/null <<EOF
[Unit]
Description=Parlons Cloud - always-on Parlons account ($USER)
After=network-online.target
Wants=network-online.target

[Service]
User=$USER
ExecStart=$JAVA_BIN -Xmx$HEAP -jar $BIN/parlons-cloud.jar $ARGS
Restart=on-failure
RestartSec=10
StandardOutput=append:$DATA/log/cloud.log
StandardError=append:$DATA/log/cloud.log

[Install]
WantedBy=multi-user.target
EOF
    $SUDO systemctl daemon-reload; $SUDO systemctl enable --now parlons-cloud >/dev/null
    note "systemd unit: $UNIT_SYS"
else
    note "no launchd/systemd here: starting in the background (it will NOT survive a reboot;"
    note "run  parlons start  after one)"
    nohup "$JAVA_BIN" -Xmx"$HEAP" -jar "$BIN/parlons-cloud.jar" $ARGS >> "$DATA/log/cloud.log" 2>&1 &
    echo $! > "$DATA/cloud.pid"
fi
if [ "$MODE" = relay ] && [ "$SUDO" != "__none__" ] && command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | head -1 | grep -qw active; then
    $SUDO ufw allow 9501/tcp >/dev/null && note "ufw: opened 9501/tcp for the relay"
fi

# ---------- 4. wait, pair this terminal, mint the phone's invite ----------
say "[4/5] Waiting for the account to come online"
for i in $(seq 1 90); do [ -s "$DATA/invite.txt" ] && break; sleep 1; done
[ -s "$DATA/invite.txt" ] || { note "not up after 90 s - last log lines:"; tail -n 20 "$DATA/log/cloud.log" 2>/dev/null; die "Fix the cause and re-run; nothing is lost."; }
ADDR="$(cat "$DATA/account.txt")"
if [ ! -s "$CLIENT/cloud.txt" ] || ! "$BIN/parlons" ping >/dev/null 2>&1; then
    CODE="$(cat "$DATA/pair-code.txt" 2>/dev/null || true)"
    "$BIN/parlons" connect "$ADDR" >/dev/null
    if [ -n "$CODE" ]; then "$BIN/parlons" pair "$CODE" >/dev/null || true; fi
fi
INVITE="$("$BIN/parlons" newcode 2>/dev/null | grep -oE 'MAX#[^ ]+\?code=[A-Z0-9-]+' | head -1 || true)"
[ -n "$INVITE" ] || INVITE="$(cat "$DATA/invite.txt")"

# ---------- 5. PATH + hand-over ----------
say "[5/5] The parlons command"
for rc in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.profile"; do
    [ -f "$rc" ] || continue
    grep -q 'parlons/bin' "$rc" 2>/dev/null || printf '\n# Parlons Cloud\nexport PATH="%s:$PATH"\n' "$BIN" >> "$rc"
done
export PATH="$BIN:$PATH"

printf '\n\033[1m  Your Parlons account is running (Parlons Cloud %s).\033[0m\n\n' "$VER"
echo "  Address (share it; it never changes):"
echo "  $ADDR"
echo
echo "  Pair your phone: open the Parlons Cloud app, tap Scan account QR, point it at this:"
echo
if command -v qrencode >/dev/null 2>&1; then qrencode -t ANSIUTF8 -m 2 "$INVITE"; fi
echo "  or paste the invite (the code half works once):"
echo "  $INVITE"
echo
echo "  Afterwards:  parlons devices | parlons newcode | parlons status | parlons log | parlons stop"
echo "  (open a new terminal, or run:  export PATH=\"$BIN:\$PATH\")"
echo
printf '\033[33m  BACK UP YOUR SEED NOW:  %s/seed.txt  -  it is your identity AND a wallet.\033[0m\n\n' "$DATA"
