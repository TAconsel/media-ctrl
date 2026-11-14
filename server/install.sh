#!/bin/sh
# Install mediactrl-server as a systemd user service.
# With --firewall, also open its port (and mDNS) to private LAN ranges in ufw (uses sudo).
set -e
here=$(cd "$(dirname "$0")" && pwd)
port=47800

install -Dm755 "$here/mediactrl-server" "$HOME/.local/bin/mediactrl-server"
install -Dm644 "$here/mediactrl-server.service" "$HOME/.config/systemd/user/mediactrl-server.service"
systemctl --user daemon-reload
systemctl --user enable mediactrl-server.service
systemctl --user restart mediactrl-server.service
echo "mediactrl-server installed and running (systemctl --user status mediactrl-server)"

if [ "$1" = "--firewall" ]; then
    for net in 192.168.0.0/16 10.0.0.0/8 172.16.0.0/12; do
        sudo ufw allow from "$net" to any port "$port" proto tcp comment 'Media Control'
        sudo ufw allow from "$net" to any port 5353 proto udp comment 'mDNS'
    done
else
    echo "If ufw is active, allow tcp/$port from the LAN:  $0 --firewall"
fi
