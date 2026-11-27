package dev.aura.punishments.v13;

import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Repo;
import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Simple.CaseKind;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.*;
import dev.aura.punishments.service.JudicialMode.State;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.support.Fx;
import dev.aura.punishments.util.Result.Code;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static dev.aura.punishments.v13.V13Support.fx;
import static dev.aura.punishments.v13.V13Support.r;
import static org.junit.jupiter.api.Assertions.*;

/** The judicial.enabled switch: install defaults, the three states, and the rule that nothing is ever resolved or released automatically. */
class JudicialModeTest {
    Fx fx;

    @AfterEach
    void tearDown() {
        if (fx != null) fx.close();
    }

    static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(JudicialModeTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ defaults
    @Test
    void theFileShippedToAFreshInstallHasTheJudicialSystemOff() throws Exception {
        assertFalse(Settings.parse(shipped()).judicial.enabled());
    }

    @Test
    void anUpgradedConfigWithoutTheKeyKeepsTheJudicialSystemOn() throws Exception {
        YamlConfiguration old = shipped();
        old.set("judicial.enabled", null);
        assertFalse(old.contains("judicial.enabled"));
        assertTrue(Settings.parse(old).judicial.enabled(), "a missing key must not change behaviour on upgrade");
    }

    @Test
    void anExplicitValueAlwaysWins() throws Exception {
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", true);
        assertTrue(Settings.parse(y).judicial.enabled());
        y.set("judicial.enabled", false);
        assertFalse(Settings.parse(y).judicial.enabled());
    }

    // ------------------------------------------------------------------ states
    @Test
    void enabledMeansEnabledRegardlessOfOpenWork() throws Exception {
        fx = fx(true);
        assertEquals(State.ENABLED, fx.core.mode.state());
        assertTrue(fx.core.mode.judicialVisible());
    }

    @Test
    void disabledWithNothingOpenIsPlainDisabled() throws Exception {
        fx = fx(false);
        fx.core.mode.refresh().get();
        assertEquals(State.DISABLED, fx.core.mode.state());
        assertFalse(fx.core.mode.judicialVisible());
        assertTrue(fx.core.mode.report().known());
        assertEquals(0, fx.core.mode.report().blocking());
    }

    @Test
    void beforeTheFirstCountTheSystemAssumesWorkMayBeOpen() throws Exception {
        fx = fx(false);
        assertEquals(State.WIND_DOWN, fx.core.mode.state(), "never claim 'nothing open' before it has been counted");
    }

    private Case openCase(Fx f, String name) throws Exception {
        Actor o = Fx.owner();
        return r(f.cases.create(o, new CaseService.CreateRequest(Fx.target(name), "other", Severity.MODERATE,
                "Griefing the spawn area repeatedly", null, List.of(Fx.ev())))).orThrow();
    }

    /** Build a database that has open judicial work, then reopen it with the switch off (what an admin does by editing config.yml). */
    private void withOpenWorkThenSwitchOff() throws Exception {
        fx = fx(true);
        Case c = openCase(fx, "Alex");
        fx.online.add(c.accusedUuid());
        r(fx.custody.initiate(Fx.owner(), c.id())).orThrow();
        openCase(fx, "Blake");
        // the admin edits config.yml and reloads
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", false);
        fx.core.settings = Settings.parse(y);
    }

    @Test
    void switchingOffWithOpenCasesAndADetaineeIsAWindDownNotAReleaseOrADeletion() throws Exception {
        withOpenWorkThenSwitchOff();
        fx.core.mode.refresh().get();
        assertEquals(State.WIND_DOWN, fx.core.mode.state());
        var rep = fx.core.mode.report();
        assertEquals(2, rep.openCases());
        assertEquals(1, rep.pretrialDetainees());
        assertFalse(rep.lines().isEmpty());
        // nothing was touched: the detainee is still held, both cases are still open, nothing was deleted
        assertEquals(1, fx.jail.prisoners.size(), "the detainee must not be released by a configuration change");
        assertEquals(2L, (long) fx.db.<Long>txSync(c -> Repo.Cases.countByStatuses(c, List.of(CaseStatus.OPEN))));
        assertEquals(1, fx.db.<Integer>txSync(c -> Repo.Custodies.allLive(c).size()));
    }

    @Test
    void windDownRefusesNewJudicialWorkButLetsExistingWorkBeFinished() throws Exception {
        withOpenWorkThenSwitchOff();
        Actor owner = Fx.owner();
        // new judicial work is refused, with a clear explanation
        var newCase = r(fx.cases.create(owner, new CaseService.CreateRequest(Fx.target("Casey"), "other", Severity.MINOR, "Spamming the chat again", null, List.of(Fx.ev()))));
        assertEquals(Result_UNAVAILABLE(), newCase.code());
        assertTrue(newCase.message().contains("switched off"));
        Case alexCase = fx.db.txSync(c -> Repo.Cases.forAccused(c, Fx.target("Alex").uuid(), 5, 0).get(0));
        fx.online.add(Fx.target("Dana").uuid());
        Case blake = fx.db.txSync(c -> Repo.Cases.forAccused(c, Fx.target("Blake").uuid(), 5, 0).get(0));
        assertEquals(Code.UNAVAILABLE, r(fx.custody.initiate(owner, blake.id())).code(), "no new pretrial detention");
        var direct = r(fx.direct.direct(owner, new DirectService.DirectRequest(Fx.target("Dana"), "other", Severity.SEVERE, "Serious griefing across many builds", "Obvious and repeated",
                List.of(Fx.ev()), PunishType.BAN, dev.aura.punishments.util.Durations.PERMANENT, null)));
        assertEquals(Code.UNAVAILABLE, direct.code(), "direct punishments exist to bypass the judicial process");
        // ...but the existing detainee can be released through the normal tool, and the case closed
        r(fx.custody.release(owner, alexCase.id(), "Released while the judicial system is wound down")).orThrow();
        assertTrue(fx.jail.prisoners.isEmpty());
        var closed = r(fx.cases.transition(owner, alexCase.id(), fx.db.<Case>txSync(c -> Repo.Cases.get(c, alexCase.id())).version(), CaseStatus.CLOSED, "Closing the case during wind-down"));
        assertTrue(closed.isOk(), closed.message());
        fx.core.mode.refresh().get();
        assertEquals(1, fx.core.mode.report().openCases(), "only Blake's case is still open");
    }

    private static Code Result_UNAVAILABLE() {
        return Code.UNAVAILABLE;
    }

    @Test
    void whenTheLastItemIsResolvedTheStateBecomesDisabled() throws Exception {
        fx = fx(true);
        Case c = openCase(fx, "Alex");
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", false);
        fx.core.settings = Settings.parse(y);
        fx.core.mode.refresh().get();
        assertEquals(State.WIND_DOWN, fx.core.mode.state());
        assertTrue(r(fx.cases.transition(Fx.owner(), c.id(), c.version(), CaseStatus.CLOSED, "Closed while winding down")).isOk());
        fx.core.mode.refresh().get();
        assertEquals(State.DISABLED, fx.core.mode.state());
    }

    @Test
    void aFinishedCaseCannotBeReopenedOnceTheSystemIsOff() throws Exception {
        fx = fx(true);
        Actor owner = Fx.owner();
        Case c = openCase(fx, "Alex");
        c = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.CLOSED, "Closed before the switch is turned off")).orThrow();
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", false);
        fx.core.settings = Settings.parse(y);
        var reopen = r(fx.cases.transition(owner, c.id(), c.version(), CaseStatus.UNDER_REVIEW, "Trying to bring the case back"));
        assertEquals(Code.UNAVAILABLE, reopen.code());
    }

    @Test
    void switchingBackOnRestoresEverythingExactly() throws Exception {
        withOpenWorkThenSwitchOff();
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", true);
        fx.core.settings = Settings.parse(y);
        assertEquals(State.ENABLED, fx.core.mode.state());
        var again = r(fx.cases.create(Fx.owner(), new CaseService.CreateRequest(Fx.target("Casey"), "other", Severity.MINOR, "Spamming the chat once more", null, List.of(Fx.ev()))));
        assertTrue(again.isOk(), again.message());
    }

    @Test
    void historicalCasesStayReadableWhileTheSwitchIsOff() throws Exception {
        fx = fx(true);
        Actor owner = Fx.owner();
        Case c = openCase(fx, "Alex");
        YamlConfiguration y = shipped();
        y.set("judicial.enabled", false);
        fx.core.settings = Settings.parse(y);
        var detail = r(fx.cases.detail(owner, c.id()));
        assertTrue(detail.isOk(), "historical records must stay accessible to authorised staff");
        assertEquals(c.id(), detail.value().c().id());
        assertTrue(r(fx.cases.historyFor(owner, c.accusedUuid(), 0, 10)).isOk());
        var export = fx.core.mode.enabled();
        assertFalse(export);
    }
}
