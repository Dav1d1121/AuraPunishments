#!/bin/bash
# Builds the multi-server lab: MariaDB database + Velocity (modern forwarding) + five Paper backends (two Minecraft versions) +
# the AuraPunishments jar and the lab driver on each. Safe to run again: it rebuilds the lab directories from scratch.
#
#   LAB      where the lab lives                              (default /tmp/claude-0/lab)
#   PAPER263 a Paper 26.3 server directory to copy             (default /tmp/claude-0/run)
#   PAPER121 a Paper 1.21.11 server directory to copy          (default /tmp/claude-0/run121)
#   JAR      the AuraPunishments jar under test
set -e
LAB=${LAB:-/tmp/claude-0/lab}
PAPER263=${PAPER263:-/tmp/claude-0/run}
PAPER121=${PAPER121:-/tmp/claude-0/run121}
REPO=$(cd "$(dirname "$0")/../.." && pwd)
JAR=${JAR:-$REPO/build/libs/AuraPunishments-1.4.0.jar}
DRV263=$REPO/live-test/build/libs/APLiveTest.jar
DRV121=$REPO/live-test-121/build/libs/APLiveTest121.jar
JAILRELOADED=$PAPER263/plugins/JailReloaded-1.2.2.jar
LUCKPERMS=$PAPER263/plugins/LuckPerms-Bukkit-5.5.71.jar
DB=${DB:-aura_lab}
SECRET=lab-forwarding-secret-1234

mkdir -p "$LAB"
for s in velocity lobby survival towny skyblock jail; do pkill -f "$LAB/$s/" 2>/dev/null || true; done
sleep 1
rm -rf "$LAB"/*/ 2>/dev/null || true

# ---- the database
mysql -uroot -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4; GRANT ALL ON $DB.* TO 'aura'@'%'; GRANT ALL ON $DB.* TO 'aura'@'localhost'; FLUSH PRIVILEGES;" 2>/dev/null \
  || mysql -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4; GRANT ALL ON $DB.* TO 'aura'@'%'; GRANT ALL ON $DB.* TO 'aura'@'localhost'; FLUSH PRIVILEGES;"

LUCKPERMS_LIBS=$PAPER263/plugins/LuckPerms/libs
mysql -uroot -e "DROP DATABASE IF EXISTS aura_lp; CREATE DATABASE aura_lp CHARACTER SET utf8mb4; GRANT ALL ON aura_lp.* TO 'aura'@'%'; GRANT ALL ON aura_lp.* TO 'aura'@'localhost'; FLUSH PRIVILEGES;"

# ---- Velocity
mkdir -p "$LAB/velocity"
if [ ! -f "$LAB/velocity.jar" ]; then
  URL=$(curl -s https://fill.papermc.io/v3/projects/velocity/versions/${VELOCITY:-4.3.1}/builds/latest | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')
  curl -sL -o "$LAB/velocity.jar" "$URL"
fi
cp "$LAB/velocity.jar" "$LAB/velocity/velocity.jar"
printf '%s' "$SECRET" > "$LAB/velocity/forwarding.secret"
cat > "$LAB/velocity/velocity.toml" <<TOML
config-version = "2.9"
bind = "127.0.0.1:25577"
motd = "AuraPunishments network lab"
show-max-players = 50
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
sample-players-in-ping = false
enable-player-address-logging = true

[ping-passthrough]
version = false
players = false
description = false
favicon = false
modinfo = false

[servers]
lobby = "127.0.0.1:25601"
survival = "127.0.0.1:25602"
towny = "127.0.0.1:25603"
skyblock = "127.0.0.1:25604"
jail = "127.0.0.1:25605"
try = ["lobby"]

[forced-hosts]
"lobby.lab" = ["lobby"]
"survival.lab" = ["survival"]
"towny.lab" = ["towny"]
"skyblock.lab" = ["skyblock"]
"jail.lab" = ["jail"]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true

[query]
enabled = false
TOML

# ---- the backends: name:port:version
backend() { # name port version
  local n=$1 port=$2 ver=$3 src drv
  if [ "$ver" = "1.21.11" ]; then src=$PAPER121; drv=$DRV121; else src=$PAPER263; drv=$DRV263; fi
  local d=$LAB/$n
  mkdir -p "$d/plugins/APLiveTest" "$d/config"
  cp "$src/paper.jar" "$d/paper.jar"
  cp -r "$src/cache" "$src/libraries" "$src/versions" "$d/" 2>/dev/null || true
  [ -d "$src/world" ] && cp -r "$src/world" "$d/world" && rm -f "$d/world/session.lock" "$d/world/uid.dat"
  [ -d "$src/world_nether" ] && cp -r "$src/world_nether" "$d/world_nether"
  [ -d "$src/world_the_end" ] && cp -r "$src/world_the_end" "$d/world_the_end"
  echo "eula=true" > "$d/eula.txt"
  cp "$src/server.properties" "$d/server.properties"
  sed -i "s/^server-port=.*/server-port=$port/; s/^online-mode=.*/online-mode=false/; s/^motd=.*/motd=$n/; s/^server-ip=.*/server-ip=127.0.0.1/; s/^level-name=.*/level-name=world/" "$d/server.properties"
  grep -q '^accepts-transfers' "$d/server.properties" || echo "accepts-transfers=false" >> "$d/server.properties"
  cp "$src/config/"*.yml "$d/config/"
  python3 - "$d/config/paper-global.yml" "$SECRET" <<'PY'
import re,sys
p,secret=sys.argv[1:3]
s=open(p).read()
s=re.sub(r"(  velocity:\n    enabled: )false(\n    online-mode: )true(\n    secret: )''", r"\g<1>true\g<2>false\g<3>'"+secret+"'", s)
open(p,'w').write(s)
PY
  cp "$JAR" "$d/plugins/AuraPunishments-1.4.0.jar"
  cp "$drv" "$d/plugins/"
  echo net > "$d/plugins/APLiveTest/phase.txt"
  mkdir -p "$d/plugins/AuraPunishments"
  unzip -p "$JAR" config.yml > "$d/plugins/AuraPunishments/config.yml"
  python3 - "$d/plugins/AuraPunishments/config.yml" "$n" "$DB" <<'PY'
import re,sys
p,name,db=sys.argv[1:4]
s=open(p).read()
def setkey(section, key, value, s):
    # replace the first "  key: ..." line that follows "section:" at column 0
    pat=re.compile(r"(\n"+re.escape(section)+r":\n(?:(?:  .*|\s*)\n)*?  "+re.escape(key)+r": )[^\n]*")
    if not pat.search(s): raise SystemExit("missing "+section+"."+key)
    return pat.sub(lambda m: m.group(1)+value, s, 1)
s=setkey("network","server-name",'"'+name+'"',s)
s=setkey("network","sync-interval-ms","500",s)
s=setkey("network","heartbeat-seconds","5",s)
s=setkey("database","type","mariadb",s)
s=setkey("database","name",db,s)
s=setkey("database","username","aura",s)
s=setkey("database","password",'"aurapass"',s)
s=setkey("database","allow-migration","true",s)
# the jail backend and where released prisoners go
s=s.replace('    backend: ""\n    # Where a released prisoner is sent.','    backend: "jail"\n    # Where a released prisoner is sent.',1)
open(p,'w').write(s)
PY
}
backend lobby 25601 26.3
backend survival 25602 26.3
backend towny 25603 1.21.11
backend skyblock 25604 1.21.11
backend jail 25605 26.3
# only the jail backend has JailReloaded (and a jail built in its world, named netjail by the orchestrator)
cp "$JAILRELOADED" "$LAB/jail/plugins/"
python3 - "$LAB/jail/plugins/AuraPunishments/config.yml" <<'PY'
import re,sys
p=sys.argv[1]
s=open(p).read()
n=re.subn(r'(\n  jail:\n(?:    #.*\n)*    jail-name: )""', r'\1"netjail"', s, 1)
if n[1]!=1: raise SystemExit("jail-name key not found")
open(p,'w').write(n[0])
PY
# LuckPerms with ONE shared MariaDB storage on lobby, survival and towny: the staff levels are the same on every backend
for n in lobby survival towny; do
  mkdir -p "$LAB/$n/plugins/LuckPerms"
  cp "$LUCKPERMS" "$LAB/$n/plugins/"
  [ -d "$LUCKPERMS_LIBS" ] && cp -r "$LUCKPERMS_LIBS" "$LAB/$n/plugins/LuckPerms/libs"
  cat > "$LAB/$n/plugins/LuckPerms/config.yml" <<LPCFG
server: $n
storage-method: MariaDB
data:
  address: 127.0.0.1:3306
  database: aura_lp
  username: aura
  password: aurapass
messaging-service: none
sync-time: -1
LPCFG
done
echo "lab ready in $LAB"
