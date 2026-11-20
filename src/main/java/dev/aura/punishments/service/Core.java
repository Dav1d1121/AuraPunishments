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
