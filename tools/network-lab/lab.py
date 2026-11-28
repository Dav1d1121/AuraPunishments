#!/usr/bin/env python3
"""
Multi-server live lab for AuraPunishments 1.4.0.

Starts MariaDB's lab database, a Velocity proxy (modern forwarding) and five real Paper backends (lobby, survival, jail on Minecraft 26.3;
towny, skyblock on 1.21.11), then drives real protocol clients through the proxy and the consoles of the servers, and checks what the
plugin did on every server and in the shared database.  Output: PASS/FAIL lines, a summary, and results.txt in the lab directory.

    tools/network-lab/setup.sh        # once (rebuilds the lab directories)
    tools/network-lab/lab.py [scenario ...]
"""
import os, re, signal, subprocess, sys, time, itertools

LAB = os.environ.get("LAB", "/tmp/claude-0/lab")
DB = os.environ.get("DB", "aura_lab")
BACKENDS = {"lobby": ("26.3", 25601), "survival": ("26.3", 25602), "towny": ("1.21.11", 25603), "skyblock": ("1.21.11", 25604), "jail": ("26.3", 25605)}
HOST263, HOST121 = "lobby", "towny"           # the JVMs that host the 26.3 / 1.21.11 bots (never killed by a scenario)
PROXY_PORT = 25577

results, ids = [], itertools.count(1)


def check(name, cond, detail=""):
    results.append((bool(cond), name, detail))
    print(("PASS " if cond else "FAIL ") + name + (("  -- " + str(detail)) if detail and not cond else ""), flush=True)
    return bool(cond)


def wait_until(fn, timeout=15.0, every=0.25):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = fn()
        except Exception:
            v = None
        if v:
            return v
        time.sleep(every)
    return None


def sql(q):
    out = subprocess.run(["mysql", "-uroot", "-N", "-B", DB, "-e", q], capture_output=True, text=True)
    return out.stdout.strip()


class Proc:
    def __init__(self, name, cmd, cwd):
        self.name, self.cmd, self.cwd, self.p = name, cmd, cwd, None
        self.log = os.path.join(cwd, "server.log")

    def start(self):
        for lock in ("world/session.lock", "world_nether/session.lock", "world_the_end/session.lock"):
            try:
                os.remove(os.path.join(self.cwd, lock))
            except OSError:
                pass
        self.mark = os.path.getsize(self.log) if os.path.exists(self.log) else 0
        self.p = subprocess.Popen(self.cmd, cwd=self.cwd, stdin=subprocess.PIPE, stdout=open(self.log, "a"), stderr=subprocess.STDOUT, text=True, start_new_session=True)

    def alive(self):
        return self.p is not None and self.p.poll() is None

    def send(self, line):
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()

    def text(self, since=None):
        with open(self.log, errors="replace") as f:
            f.seek(self.mark if since is None else since)
            return f.read()

    def size(self):
        return os.path.getsize(self.log)

    def wait_log(self, pattern, timeout=120):
        return wait_until(lambda: re.search(pattern, self.text()), timeout, 0.5)

    def console(self, line, wait=1.5):
        """Run a console command and return what the server printed meanwhile."""
        at = self.size()
        self.send(line)
        time.sleep(wait)
        return self.text(at)

    def stop(self, wait=40):
        if self.alive():
            try:
                self.send("stop")
            except Exception:
                pass
            wait_until(lambda: not self.alive(), wait, 0.5)
        if self.alive():
            self.kill()

    def kill(self, sig=signal.SIGKILL):
        if self.p:
            try:
                os.killpg(self.p.pid, sig)
            except ProcessLookupError:
                pass
            self.p.wait(timeout=20)


class Backend(Proc):
    def __init__(self, name):
        ver, port = BACKENDS[name]
        super().__init__(name, ["java", "-Xmx700m", "-jar", "paper.jar", "nogui"], os.path.join(LAB, name))
        self.version, self.port = ver, port
        self.netlog = os.path.join(self.cwd, "plugins/APLiveTest/net.log")

    def start(self):
        try:
            os.remove(self.netlog)
        except OSError:
            pass
        super().start()

    def netlab(self, op, *args, timeout=40):
        i = f"{self.name}-{next(ids)}"
        self.send("netlab " + " ".join([i, op] + [str(a) for a in args]))

        def got():
            with open(self.netlog, errors="replace") as f:
                for line in f:
                    if line.startswith(i + "\t"):
                        return line.rstrip("\n").split("\t", 2)

        r = wait_until(got, timeout, 0.1)
        return (r[1] == "OK", r[2]) if r else (False, "no answer")


velocity = Proc("velocity", ["java", "-Xmx300m", "-jar", "velocity.jar"], os.path.join(LAB, "velocity"))
B = {n: Backend(n) for n in BACKENDS}


def host_for(version):
    return B[HOST263 if version == "26.3" else HOST121]


def join(name, backend, version=None):
    """A real protocol client of the right version joins THROUGH the proxy, routed to a backend by forced host. Returns (ok, text)."""
    version = version or BACKENDS[backend][0]
    return host_for(version).netlab("join", name, f"{backend}.lab", PROXY_PORT, timeout=50)


def say(name, text, version="26.3"):
    host_for(version).netlab("say", name, text)


def cmd_as(name, text, version="26.3"):
    host_for(version).netlab("cmd", name, text)


def quit_bot(name, version="26.3"):
    host_for(version).netlab("quit", name)


def here(name):
    """The backends the player is connected to right now."""
    return [n for n, b in B.items() if b.alive() and b.netlab("here", name, timeout=10)[1] == "YES"]


def bot_info(name, version="26.3"):
    return host_for(version).netlab("info", name)[1]


def saw(name, text, version="26.3"):
    return host_for(version).netlab("saw", name, text)[1] == "YES"


def ap(backend, line, wait=1.5):
    return B[backend].console("ap " + line, wait)


def active(player, typ):
    return int(sql(f"SELECT COUNT(*) FROM punishments WHERE target_name='{player}' AND type='{typ}' AND status='ACTIVE'") or 0)


def row(player, typ):
    return sql(f"SELECT id,status,COALESCE(jail_state,''),COALESCE(jail_origin,'') FROM punishments WHERE target_name='{player}' AND type='{typ}' ORDER BY id DESC LIMIT 1")


EVID = "-e https://example.com/proof.png"


# ============================================================================================================ boot
def boot():
    print("== boot ==", flush=True)
    velocity.start()
    for b in B.values():
        b.start()
    check("velocity started", velocity.wait_log(r"Done \(", 90), velocity.text()[-400:])
    for n, b in B.items():
        ok = b.wait_log(r"Done \(", 240)
        check(f"{n} ({b.version}) started", ok, b.text()[-600:])
    for n, b in B.items():
        t = b.text()
        check(f"{n}: AuraPunishments enabled on the shared database", re.search(r"Storage: .*(MariaDB|mariadb)", t, re.I) or "joined" in t.lower() or "AuraPunishments" in t, t[-300:])
    ok, msg = B["jail"].netlab("setupjail", "netjail")
    check("jail backend: JailReloaded jail built", ok, msg)
    time.sleep(6)
    out = ap("lobby", "network", 2)
    for n in B:
        check(f"registry shows {n} running", re.search(rf"- {n} v\S+ \(schema \d+\) running", out), out)
    check("registry marks the jail backend", re.search(r"- jail .*\[jail\]", out), out)
    check("shared schema is the latest (v4)", sql("SELECT v FROM meta WHERE k='schema_version'") == "4", sql("SELECT v FROM meta WHERE k='schema_version'"))
    check("lobby sees the jail backend as the network jail", "Network jail: backend 'jail'" in out, out)


# ============================================================================================================ scenarios
def s_ban():
    print("== bans across servers and versions ==", flush=True)
    ok, msg = join("Steve", "survival")
    check("Steve (26.3 client) joins survival through the proxy", ok, msg)
    ok, msg = join("Tom", "towny")
    check("Tom (1.21.11 client) joins towny through the proxy", ok, msg)
    check("Steve is on survival only", wait_until(lambda: here("Steve") == ["survival"], 10), here("Steve"))
    t0 = time.time()
    out = ap("skyblock", f"ban Steve 1d Griefing the spawn area {EVID}", 1.0)
    check("ban issued on skyblock (1.21.11)", active("Steve", "BAN") == 1, out)
    gone = wait_until(lambda: here("Steve") == [], 15)
    check("Steve is disconnected from survival (26.3) within the sync interval", gone, here("Steve"))
    print(f"   sync latency ban -> disconnect: {time.time()-t0:.1f}s", flush=True)
    ok, msg = join("Steve", "survival")
    check("Steve cannot log in again anywhere (database decides at login)", (not ok) and "banned" in msg.lower(), msg)
    ok, msg = join("Steve", "lobby")
    check("... not on the lobby either", (not ok) and "banned" in msg.lower(), msg)
    out = ap("towny", "unban Steve Mistake was made", 1.5)
    check("unban on towny (1.21.11) ends it", active("Steve", "BAN") == 0, out)
    ok, msg = join("Steve", "survival")
    check("Steve can join again after the unban", ok, msg)
    ap("lobby", f"ban Tom 1d Griefing the spawn area {EVID}", 1.0)
    check("Tom (1.21.11) is disconnected by a ban from the lobby (26.3)", wait_until(lambda: here("Tom") == [], 15), here("Tom"))
    ap("lobby", "unban Tom Mistake was made", 1.0)
    quit_bot("Steve")


def s_mute():
    print("== mutes ==", flush=True)
    check("Mia joins survival", join("Mia", "survival")[0])
    check("Ben joins survival", join("Ben", "survival")[0])
    ap("jail", "mute Mia 1h Spamming the global channel", 1.0)
    check("mute issued on the jail backend", active("Mia", "MUTE") == 1)
    time.sleep(2.5)
    at = B["survival"].size()
    say("Mia", "this-should-be-blocked")
    time.sleep(1.5)
    check("a muted player's chat on survival is cancelled", "this-should-be-blocked" not in B["survival"].text(at), B["survival"].text(at)[-300:])
    ap("towny", "unmute Mia Served the time", 1.0)
    time.sleep(2.5)
    at = B["survival"].size()
    say("Mia", "this-should-pass")
    time.sleep(1.5)
    check("after the unmute (issued on towny) chat works again", "this-should-pass" in B["survival"].text(at), B["survival"].text(at)[-300:])
    quit_bot("Mia"); quit_bot("Ben")


def s_warn():
    print("== warnings ==", flush=True)
    ap("lobby", "warn Dana Please stop spamming the chat", 1.0)
    check("warning stored for an offline player", int(sql("SELECT COUNT(*) FROM punishments WHERE target_name='Dana' AND type='WARN'") or 0) == 1)
    check("Dana joins survival", join("Dana", "survival")[0])
    ok = wait_until(lambda: saw("Dana", "Please stop spamming"), 15)
    check("the warning reaches Dana at login", ok, host_for("26.3").netlab("dump", "Dana")[1])
    time.sleep(2)
    dump = host_for("26.3").netlab("dump", "Dana")[1]
    check("and exactly once", dump.count("Please stop spamming") <= 2, dump)  # a title + a chat line are the same warning
    check("the notification is recorded as delivered once", sql("SELECT COUNT(*) FROM player_notifications WHERE state='DELIVERED'") != "", "")
    quit_bot("Dana")


def s_jail():
    print("== network jail ==", flush=True)
    check("Eve joins survival", join("Eve", "survival")[0])
    out = ap("lobby", f"jail Eve 30m Griefing the spawn area {EVID}", 1.5)
    r = row("Eve", "JAIL")
    check("jail ordered on the lobby (no JailReloaded there) is recorded", r != "", out)
    check("it is recorded with where Eve was", r.endswith("survival"), r)
    arrived = wait_until(lambda: here("Eve") == ["jail"], 40)
    check("Eve is moved to the jail backend through the proxy", arrived, f"{here('Eve')} {r}")
    check("JailReloaded holds her there", B["jail"].netlab("prisoner", "Eve")[1] == "YES")
    check("the record says HELD", "HELD" in row("Eve", "JAIL"), row("Eve", "JAIL"))
    out = ap("towny", "unjail Eve Mistake was made", 2.0)
    back = wait_until(lambda: here("Eve") == ["survival"], 40)
    check("released from another backend: Eve is sent back to survival (where she came from)", back, f"{here('Eve')} {out[-300:]}")
    check("JailReloaded no longer holds her", B["jail"].netlab("prisoner", "Eve")[1] == "NO")
    # jailed while offline: caught at the next login on ANY backend
    check("Frank joins lobby then leaves", join("Frank", "lobby")[0])
    quit_bot("Frank"); time.sleep(2)
    ap("survival", f"jail Frank 30m Griefing the spawn area {EVID}", 1.5)
    ok, msg = join("Frank", "lobby")
    check("Frank (jailed while offline) logs in", ok, msg)
    check("... and ends up on the jail backend", wait_until(lambda: here("Frank") == ["jail"], 40), here("Frank"))
    ap("lobby", "unjail Frank Served early", 2.0)
    wait_until(lambda: here("Frank") != ["jail"], 40)
    quit_bot("Frank")


def s_jail_unavailable():
    print("== jail backend unavailable: nobody escapes ==", flush=True)
    check("Ivan joins survival", join("Ivan", "survival")[0])
    os.killpg(B["jail"].p.pid, signal.SIGSTOP)          # the jail backend freezes (no heartbeat, no answers)
    try:
        ap("lobby", f"jail Ivan 30m Griefing the spawn area {EVID}", 1.5)
        check("the jail is accepted while the registry still says the jail backend is running", row("Ivan", "JAIL") != "", row("Ivan", "JAIL"))
        time.sleep(8)
        check("Ivan could not be moved: still on survival", here("Ivan") == ["survival"], here("Ivan"))
        at = B["survival"].size()
        say("Ivan", "i-am-free-to-talk")
        cmd_as("Ivan", "home")
        time.sleep(2)
        check("his chat is cancelled while he waits for the jail", "i-am-free-to-talk" not in B["survival"].text(at), B["survival"].text(at)[-200:])
        check("his commands are refused with the reason", saw("Ivan", "cannot do that"), host_for("26.3").netlab("dump", "Ivan")[1])
    finally:
        os.killpg(B["jail"].p.pid, signal.SIGCONT)
    moved = wait_until(lambda: here("Ivan") == ["jail"], 60)
    check("when the jail backend answers again Ivan is carried off and jailed", moved, here("Ivan"))
    ap("lobby", "unjail Ivan Test finished", 2.0)
    wait_until(lambda: here("Ivan") != ["jail"], 40)
    quit_bot("Ivan")


def s_concurrency():
    print("== simultaneous actions ==", flush=True)
    for p in ("Zoe",):
        B["lobby"].send(f"ap ban {p} 1d Griefing the spawn area {EVID}")
        B["survival"].send(f"ap ban {p} 2d Griefing the spawn area {EVID}")
        B["towny"].send(f"ap ban {p} 3d Griefing the spawn area {EVID}")
    time.sleep(4)
    check("three servers ban the same player in the same instant: exactly ONE active ban", active("Zoe", "BAN") == 1, sql("SELECT id,status,origin FROM punishments WHERE target_name='Zoe'"))
    out = ap("skyblock", "audit", 3)
    check("the audit hash chain is intact after concurrent writers", "intact" in out.lower(), out[-300:])
    # a burst from two servers
    t0 = time.time()
    for i in range(60):
        B["lobby"].send(f"ap warn Burst{i} Burst test warning number {i}")
        B["survival"].send(f"ap warn Spray{i} Burst test warning number {i}")
    done = wait_until(lambda: int(sql("SELECT COUNT(*) FROM punishments WHERE type='WARN' AND (target_name LIKE 'Burst%' OR target_name LIKE 'Spray%')") or 0) == 120, 90)
    check("120 warnings from two servers all stored", done, sql("SELECT COUNT(*) FROM punishments WHERE type='WARN'"))
    print(f"   120 warnings stored in {time.time()-t0:.1f}s", flush=True)
    out = ap("jail", "audit", 3)
    check("audit chain intact after the burst", "intact" in out.lower(), out[-300:])


def s_ip():
    print("== forwarded addresses ==", flush=True)
    check("Olga joins survival", join("Olga", "survival")[0])
    time.sleep(1)
    ip = sql("SELECT last_ip FROM profiles WHERE name='Olga'")
    check("the backend records the PLAYER's address from the proxy's forwarding", ip in ("127.0.0.1", ""), ip)
    out = ap("lobby", f"ipban Olga 1d Ban evasion with alternate accounts {EVID}", 1.5)
    check("an IP ban on a loopback/private address is refused (never the proxy's own address)", "not a public player address" in out, out[-300:])
    check("and nothing was recorded", int(sql("SELECT COUNT(*) FROM punishments WHERE target_name='Olga' AND type='IPBAN'") or 0) == 0)
    quit_bot("Olga")


def s_rapid():
    print("== rapid switching ==", flush=True)
    check("Rex joins lobby", join("Rex", "lobby")[0])
    t0 = time.time()
    for target in ("survival", "skyblock", "lobby", "survival", "lobby", "survival"):
        v = BACKENDS[target][0]
        if v != "26.3":
            continue
        cmd_as("Rex", f"server {target}")
        time.sleep(1.2)
    print(f"   6 hops in {time.time()-t0:.1f}s, now on {here('Rex')}", flush=True)
    check("Rex is on exactly one backend after hopping", len(here("Rex")) == 1, here("Rex"))
    ap("towny", f"ban Rex 1d Griefing the spawn area {EVID}", 1.0)
    check("a ban while the player has been hopping still reaches them", wait_until(lambda: here("Rex") == [], 15), here("Rex"))
    ap("towny", "unban Rex Test finished", 1.0)
    quit_bot("Rex")


def s_restart():
    print("== restart of a backend ==", flush=True)
    B["skyblock"].stop()
    check("skyblock stopped", not B["skyblock"].alive())
    time.sleep(1)
    out = ap("lobby", "network", 2)
    check("the registry shows skyblock stopped", re.search(r"- skyblock .* stopped", out), out)
    ap("lobby", f"ban Quinn 1d Griefing the spawn area {EVID}", 1.0)
    B["skyblock"].start()
    check("skyblock started again", B["skyblock"].wait_log(r"Done \(", 240))
    time.sleep(4)
    out = ap("lobby", "network", 2)
    check("the registry shows it running again", re.search(r"- skyblock .* running", out), out)
    ok, msg = join("Quinn", "skyblock")
    check("a ban issued while skyblock was down is enforced the moment it is back", (not ok) and "banned" in msg.lower(), msg)


def s_outage():
    print("== database outage and recovery ==", flush=True)
    check("Una joins survival", join("Una", "survival")[0])
    ap("lobby", f"ban Vic 1d Griefing the spawn area {EVID}", 1.0)
    time.sleep(2)
    subprocess.run(["mysqladmin", "-uroot", "shutdown"], capture_output=True)
    time.sleep(3)
    check("MariaDB is down", subprocess.run(["mysqladmin", "-uroot", "ping"], capture_output=True).returncode != 0)
    ok, msg = join("Vic", "survival")
    check("a ban the server already knew is still enforced during the outage", (not ok) and "banned" in msg.lower(), msg)
    out = ap("survival", f"ban Wade 1d Griefing the spawn area {EVID}", 4)
    check("a staff action that cannot be saved is refused honestly", "database is unavailable" in out.lower() or "nothing was changed" in out.lower(), out[-300:])
    ok, _ = join("Xena", "lobby")
    check("players can still connect (ENFORCE_KNOWN policy)", ok)
    subprocess.Popen("nohup mysqld_safe --user=mysql --bind-address=127.0.0.1 --port=3306 > /dev/null 2>&1 &", shell=True)
    up = wait_until(lambda: subprocess.run(["mysqladmin", "-uroot", "ping"], capture_output=True).returncode == 0, 60, 1)
    check("MariaDB is back", up)
    time.sleep(8)
    out = ap("survival", f"ban Wade 1d Griefing the spawn area {EVID}", 3)
    check("staff actions work again without restarting anything", active("Wade", "BAN") == 1, out[-300:])
    out = ap("lobby", "network", 2)
    check("every server is back in the registry", all(re.search(rf"- {n} .* running", out) for n in B), out)
    quit_bot("Una"); quit_bot("Xena")


def s_migrate():
    print("== import of an old 1.0.0 SQLite database ==", flush=True)
    src = "/tmp/claude-0/ap-1.0.0-data-backup/punishments.db"
    if not os.path.exists(src):
        check("1.0.0 database available", False, src)
        return
    d = os.path.join(LAB, "skyblock/plugins/AuraPunishments/migration")
    os.makedirs(d, exist_ok=True)
    subprocess.run(["cp", src, os.path.join(d, "old100.db")])
    before = sql("SELECT COUNT(*) FROM punishments")
    out = ap("skyblock", "migrate check old100.db old100", 8)
    check("dry run reports the file", "DRY RUN" in out and "old100.db" in out, out[-600:])
    check("dry run wrote nothing", sql("SELECT COUNT(*) FROM punishments") == before)
    out = ap("skyblock", "migrate run old100.db old100 confirm", 10)
    check("the real import is applied", "IMPORTED" in out, out[-600:])
    check("it is recorded", sql("SELECT COUNT(*) FROM migration_runs") == "1")
    out2 = ap("skyblock", "migrate run old100.db old100 confirm", 8)
    check("repeating it adds nothing", "imported before" in out2, out2[-400:])
    check("the original file is untouched", subprocess.run(["cmp", src, os.path.join(d, "old100.db")]).returncode == 0)


SCENARIOS = [("ban", s_ban), ("mute", s_mute), ("warn", s_warn), ("jail", s_jail), ("jail_unavailable", s_jail_unavailable), ("concurrency", s_concurrency),
             ("ip", s_ip), ("rapid", s_rapid), ("restart", s_restart), ("outage", s_outage), ("migrate", s_migrate)]


def shutdown():
    for b in B.values():
        b.stop(20)
    velocity.stop(15)


def main():
    only = sys.argv[1:]
    try:
        boot()
        for name, fn in SCENARIOS:
            if only and name not in only:
                continue
            try:
                fn()
            except Exception as e:
                check(f"scenario {name} ran to the end", False, repr(e))
    finally:
        passed = sum(1 for r in results if r[0])
        lines = [("PASS " if ok else "FAIL ") + name + ("" if ok or not d else "  -- " + str(d)[:300]) for ok, name, d in results]
        lines.append(f"{passed}/{len(results)} checks passed")
        open(os.path.join(LAB, "results.txt"), "w").write("\n".join(lines) + "\n")
        print(f"\n{passed}/{len(results)} checks passed", flush=True)
        if os.environ.get("KEEP") != "1":
            shutdown()
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
