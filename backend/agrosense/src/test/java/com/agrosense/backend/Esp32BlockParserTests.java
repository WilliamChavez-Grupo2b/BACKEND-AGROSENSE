package com.agrosense.backend;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32BlockParser;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class Esp32BlockParserTests {

    /** Exactly what firmware/esp32-tcp writes every second: Print of a float, two decimals, println at the end. */
    private static final String REAL_BLOCK = """
            ===== PLANTICAS =====\r
            Temperatura exterior: 24.50 C\r
            Humedad relativa: 60.00 %\r
            Humedad del suelo: 31.00 %\r
            \r
            """;

    private final Esp32BlockParser parser = new Esp32BlockParser();

    private List<Esp32Reading> feed(String text) {
        List<Esp32Reading> readings = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            parser.accept(line.replace("\r", "")).ifPresent(readings::add);
        }
        return readings;
    }

    @Test
    void theRealBlockGivesOneReadingWithTheThreeValues() {
        assertThat(feed(REAL_BLOCK)).containsExactly(new Esp32Reading(24.5, 60.0, 31.0));
        assertThat(parser.discardedBlocks()).isZero();
    }

    @Test
    void labelsAreMatchedWithoutCaseOrAccentsAndCommasAndSignsAreNumbers() {
        assertThat(feed("""
                ===== planticas =====
                TEMPERATURA EXTERIOR: -3,25 °C
                Humedad Relativa:   +55,5 %
                HUMEDAD DEL SÚELO: 12 %

                """)).containsExactly(new Esp32Reading(-3.25, 55.5, 12.0));
    }

    @Test
    void nanOrInfLeavesOnlyThatFieldOut() {
        assertThat(feed("""
                ===== PLANTICAS =====
                Temperatura exterior: nan C
                Humedad relativa: nan %
                Humedad del suelo: 31.00 %

                """)).containsExactly(new Esp32Reading(null, null, 31.0));
        assertThat(feed("""
                ===== PLANTICAS =====
                Temperatura exterior: inf C
                Humedad relativa: 61.00 %
                Humedad del suelo:  %

                """)).containsExactly(new Esp32Reading(null, 61.0, null));
    }

    @Test
    void aBlockWithNothingUsableIsDroppedAndCounted() {
        assertThat(feed("""
                ===== PLANTICAS =====
                Temperatura exterior: nan C
                Humedad relativa: nan %

                """)).isEmpty();
        assertThat(parser.discardedBlocks()).isEqualTo(1);
    }

    @Test
    void valuesOutsideTheirPhysicalRangeAreLeftOut() {
        assertThat(feed("""
                ===== PLANTICAS =====
                Temperatura exterior: 85.00 C
                Humedad relativa: -4.00 %
                Humedad del suelo: 100.00 %

                """)).containsExactly(new Esp32Reading(null, null, 100.0));
        assertThat(feed("""
                ===== PLANTICAS =====
                Temperatura exterior: -20.00 C
                Humedad del suelo: 101.00 %

                """)).containsExactly(new Esp32Reading(-20.0, null, null));
    }

    @Test
    void aCutBlockEndsAtTheNextHeaderAndKeepsWhatItHad() {
        assertThat(feed("""
                ===== PLANTICAS =====
                Temperatura exterior: 24.50 C
                ===== PLANTICAS =====
                Temperatura exterior: 24.60 C
                Humedad relativa: 60.00 %
                Humedad del suelo: 31.00 %

                """)).containsExactly(new Esp32Reading(24.5, null, null), new Esp32Reading(24.6, 60.0, 31.0));
    }

    @Test
    void aBlockInterruptedByTheConnectionIsFinishedByHand() {
        feed("===== PLANTICAS =====\nHumedad del suelo: 40.00 %");
        assertThat(parser.finish()).contains(new Esp32Reading(null, null, 40.0));
        assertThat(parser.finish()).isEmpty();
    }

    @Test
    void garbageOutsideAndInsideABlockIsIgnored() {
        assertThat(feed("""
                Red WiFi iniciada
                ------------------------------------------
                \u0000ÿbasura
                ===== PLANTICAS =====
                esto no es una medida
                Presion: 1013 hPa
                Humedad del suelo: 31.00 %

                """)).containsExactly(new Esp32Reading(null, null, 31.0));
    }

    @Test
    void aLineLongerThan256CharactersIsSkipped() {
        feed("===== PLANTICAS =====");
        assertThat(parser.accept("Humedad del suelo: 31.00 %" + " ".repeat(300))).isEmpty();
        assertThat(parser.accept("")).isEmpty();  // the block had nothing else
        assertThat(parser.discardedBlocks()).isEqualTo(1);
        assertThat(parser.accept("x".repeat(Esp32BlockParser.MAX_LINE + 1))).isEqualTo(Optional.empty());
    }

    @Test
    void onlyAPrivateIpv4AddressIsAccepted() {
        assertThat(Esp32TcpIngestionService.isPrivate("192.168.4.1", false)).isTrue();
        assertThat(Esp32TcpIngestionService.isPrivate("10.0.0.7", false)).isTrue();
        assertThat(Esp32TcpIngestionService.isPrivate("172.16.0.1", false)).isTrue();
        assertThat(Esp32TcpIngestionService.isPrivate("172.31.255.254", false)).isTrue();
        assertThat(Esp32TcpIngestionService.isPrivate("172.32.0.1", false)).isFalse();
        assertThat(Esp32TcpIngestionService.isPrivate("8.8.8.8", false)).isFalse();
        assertThat(Esp32TcpIngestionService.isPrivate("192.168.4.300", false)).isFalse();
        assertThat(Esp32TcpIngestionService.isPrivate("esp32.local", false)).isFalse();
        assertThat(Esp32TcpIngestionService.isPrivate("127.0.0.1", false)).isFalse();
        assertThat(Esp32TcpIngestionService.isPrivate("127.0.0.1", true)).isTrue();  // the tests' fake ESP32 only
        assertThat(Esp32TcpIngestionService.isPrivate(null, false)).isFalse();
    }
}
