# The network lab

What produced the live evidence for 1.4.0 ([docs/evidence/live-network-lab-1.4.0-*.txt](../../docs/evidence)): a real network on one machine - MariaDB, Velocity 4.3.1 (modern forwarding), five Paper backends (lobby, survival, jail on 26.3; towny, skyblock on 1.21.11), LuckPerms with one shared storage on lobby/survival/towny, JailReloaded on the jail backend - driven by real Minecraft protocol clients (through the proxy) and the server consoles.

* `setup.sh` rebuilds the lab directories from two existing Paper server directories (`PAPER263`, `PAPER121`), the AuraPunishments jar and the two lab driver jars (`gradle :live-test:jar :live-test-121:jar`), and recreates the databases `aura_lab` and `aura_lp`. It needs a local MariaDB with a user `aura` / `aurapass` and `mysql -uroot` access.
* `lab.py [scenario ...]` boots everything and runs the scenarios (`ban mute warn jail jail_unavailable concurrency ip rapid restart hierarchy outage migrate`), printing PASS/FAIL lines and writing `results.txt` in the lab directory.
* The driver (`live-test/.../NetLab.java`) is loaded as `APLiveTest` in "net" mode; the orchestrator talks to it through the `netlab` console command.

Not part of the shipped plugin.
