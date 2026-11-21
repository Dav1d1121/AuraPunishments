package dev.aura.punishments.service;

import dev.aura.punishments.model.Records.Punishment;

import java.util.UUID;

/** Effects on connected players (kick screens, notices). Always invoked on the main thread. */
public interface Enforcement {
    boolean isOnline(UUID uuid);

    /** Disconnect with the ban / ip-ban / kick screen for this punishment. */
    void disconnect(UUID uuid, Punishment p);

    void noticeMute(UUID uuid, Punishment p);

    void noticeUnmute(UUID uuid);

    /** @return true when the player was online and the notification was shown to them. */
    boolean deliverNotification(UUID uuid, dev.aura.punishments.model.Records.Notification n, boolean late);

    Enforcement NONE = new Enforcement() {
        @Override
        public boolean isOnline(UUID uuid) {
            return false;
        }

        @Override
        public void disconnect(UUID uuid, Punishment p) { }

        @Override
        public void noticeMute(UUID uuid, Punishment p) { }

        @Override
        public void noticeUnmute(UUID uuid) { }

        @Override
        public boolean deliverNotification(UUID uuid, dev.aura.punishments.model.Records.Notification n, boolean late) {
            return false;
        }
    };
}
