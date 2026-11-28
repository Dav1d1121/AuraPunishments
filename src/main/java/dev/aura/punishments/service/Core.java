package dev.aura.punishments.service;

import dev.aura.punishments.config.Messages;
import dev.aura.punishments.config.OffenseCatalog;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.db.Database;
import dev.aura.punishments.integration.JailPort;
import dev.aura.punishments.util.Clock;

import java.util.logging.Logger;

/** Shared dependencies handed to every service. Settings/offenses/jail are swapped atomically on reload. */
public final class Core {
    public final Database db;
    public final Clock clock;
    public final Logger log;
    public final MainThread main;
    public final Authority auth;
    public volatile Settings settings;
    public volatile OffenseCatalog offenses;
    public volatile JailPort jail;
    public volatile LevelResolver levels;
    public volatile DomainEvent.Sink events;
    public volatile Messages messages;
    public volatile Enforcement enforcement = Enforcement.NONE;
    /** The network side (shared database only): change sync, presence, registry. {@code null} on a standalone server. */
    public volatile NetworkService network;
    /** The network jail (hold, transfer, return); {@code null} unless network.jail.backend is set on a shared database. */
    public volatile NetworkJail netJail;
    /** Imports old per-server SQLite databases into the shared database (shared databases only). */
    public volatile SqliteImport importer;
    public final NotificationService notifications;
    public final JudicialMode mode;

    public Core(Database db, Clock clock, Logger log, MainThread main, Settings settings, OffenseCatalog offenses,
                JailPort jail, LevelResolver levels, DomainEvent.Sink events) {
        this.db = db;
        this.clock = clock;
        this.log = log;
        this.main = main;
        this.settings = settings;
        this.offenses = offenses;
        this.jail = jail;
        this.levels = levels;
        this.events = events;
        this.auth = new Authority(() -> this.settings);
        this.notifications = new NotificationService(this);
        this.mode = new JudicialMode(this);
        this.auth.denialSink((a, node, what) -> emit(DomainEvent.of(DomainEvent.Type.SECURITY).key("security:" + a.name() + ":" + node + ":" + System.currentTimeMillis() / 60_000)
                .actor(a.name()).summary("Refused: tried to " + what + " without " + node).build()));
    }

    /**
     * JailReloaded for the court system (pretrial detention, jail sentences): only a server that really has it can do those. On a network
     * the other backends have a remote port that merely queues jail PUNISHMENTS for the jail backend, which is not enough for custody.
     */
    public JailPort courtJail() {
        JailPort j = jail;
        if (j.authoritative()) return j;
        return new dev.aura.punishments.integration.UnavailableJailPort("pretrial detention and court jail sentences can only be carried out on the network's jail backend '"
                + settings.network.jail().backend() + "'; use the jail punishment from here, or do this on that backend");
    }

    public void emit(DomainEvent e) {
        try {
            events.emit(e);
        } catch (Throwable t) {
            // A notification problem must never undo or block a moderation action.
            log.warning("Event dispatch failed for " + e.type() + ": " + t.getMessage());
        }
    }
}
