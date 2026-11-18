package com.agrosense.backend.pattern.behavioral.state;

import java.time.Duration;

/**
 * State: how the link with the ESP32 is going. Each state decides which state follows each event and says itself in
 * words, so Esp32Link holds no {@code if} over the current state.
 *
 * <pre>
 * WAITING ──block──▶ RECEIVING ──no block for a while──▶ SILENT
 *    ▲                   ▲  │                               │
 *    │                   └──┼────────────block──────────────┘
 * opened                    └──lost──▶ DISCONNECTED ◀──lost── (any)
 *    └─────────────────────────────────────┘
 * </pre>
 */
public enum Esp32LinkState {

    WAITING {
        @Override
        public String label(Duration sinceLastBlock) {
            return "Conectado, esperando el primer dato del ESP32";
        }
    },
    RECEIVING {
        @Override
        public Esp32LinkState tick(Duration sinceLastBlock, Duration silentAfter) {
            return sinceLastBlock != null && sinceLastBlock.compareTo(silentAfter) > 0 ? SILENT : this;
        }

        @Override
        public String label(Duration sinceLastBlock) {
            return "Recibiendo datos del ESP32";
        }
    },
    SILENT {
        @Override
        public String label(Duration sinceLastBlock) {
            return "Conectado, pero el ESP32 no envía datos desde hace "
                    + (sinceLastBlock == null ? 0 : sinceLastBlock.toSeconds()) + " s";
        }
    },
    DISCONNECTED {
        @Override
        public Esp32LinkState connectionOpened() {
            return WAITING;
        }

        @Override
        public String label(Duration sinceLastBlock) {
            return "Sin conexión con el ESP32; se reintenta solo";
        }
    };

    /** A new connection only changes a link that had none. */
    public Esp32LinkState connectionOpened() {
        return this;
    }

    /** Any block means the ESP32 is sending again. */
    public Esp32LinkState blockReceived() {
        return RECEIVING;
    }

    public Esp32LinkState connectionLost() {
        return DISCONNECTED;
    }

    /** The passing of time: only a link that was receiving falls silent. */
    public Esp32LinkState tick(Duration sinceLastBlock, Duration silentAfter) {
        return this;
    }

    public abstract String label(Duration sinceLastBlock);
}
