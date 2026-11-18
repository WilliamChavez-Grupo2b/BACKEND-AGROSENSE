package com.agrosense.backend.pattern.behavioral.chain;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The links of the chain that reads the ESP32's blocks, in the order a line meets them:
 *
 * <ol>
 *     <li>{@link TooLong}: a line longer than the limit is skipped whole;</li>
 *     <li>{@link Header}: {@code ===== PLANTICAS =====} closes the block in progress and opens a new one;</li>
 *     <li>{@link OutsideBlock}: anything before the first header is ignored;</li>
 *     <li>{@link Blank}: the blank line closes the block;</li>
 *     <li>{@link Measure}: one per field, it reads its value or leaves it out (nan, inf, empty or out of range);</li>
 *     <li>{@link Ignored}: any other line, the last link, which takes everything.</li>
 * </ol>
 */
public final class Esp32LineHandlers {

    private Esp32LineHandlers() {
    }

    public static LineHandler chain(int maxLine, double minTemperature, double maxTemperature) {
        return LineHandler.chain(new TooLong(maxLine), new Header(), new OutsideBlock(), new Blank(),
                new Measure("temperatura exterior", minTemperature, maxTemperature, BlockInProgress::airTemperature),
                new Measure("humedad relativa", 0, 100, BlockInProgress::relativeHumidity),
                new Measure("humedad del suelo", 0, 100, BlockInProgress::soilMoisture),
                new Ignored());
    }

    @Slf4j
    public static final class TooLong extends LineHandler {

        private final int maxLine;

        public TooLong(int maxLine) {
            this.maxLine = maxLine;
        }

        @Override
        protected boolean handles(Line line, BlockInProgress block) {
            return line.raw().length() > maxLine;
        }

        @Override
        protected Optional<Esp32Reading> process(Line line, BlockInProgress block) {
            log.warn("ESP32: line of {} characters skipped, longer than {}", line.raw().length(), maxLine);
            return Optional.empty();
        }
    }

    public static final class Header extends LineHandler {

        private static final Pattern HEADER = Pattern.compile("^=+\\s*planticas\\s*=+$");

        @Override
        protected boolean handles(Line line, BlockInProgress block) {
            return HEADER.matcher(line.text()).matches();
        }

        @Override
        protected Optional<Esp32Reading> process(Line line, BlockInProgress block) {
            Optional<Esp32Reading> previous = block.close();
            block.open();
            return previous;
        }
    }

    public static final class OutsideBlock extends LineHandler {

        @Override
        protected boolean handles(Line line, BlockInProgress block) {
            return !block.isOpen();
        }

        @Override
        protected Optional<Esp32Reading> process(Line line, BlockInProgress block) {
            return Optional.empty();
        }
    }

    public static final class Blank extends LineHandler {

        @Override
        protected boolean handles(Line line, BlockInProgress block) {
            return line.text().isEmpty();
        }

        @Override
        protected Optional<Esp32Reading> process(Line line, BlockInProgress block) {
            return block.close();
        }
    }

    @Slf4j
    public static final class Measure extends LineHandler {

        /** The number before the unit: 24.50, -3,5 or +12, then C, °C or %. */
        private static final Pattern NUMBER = Pattern.compile("^([-+]?\\d+(?:[.,]\\d+)?)\\s*(?:°\\s*c|c|%)?$");

        private final String label;
        private final double low;
        private final double high;
        private final BiConsumer<BlockInProgress, Double> field;

        public Measure(String label, double low, double high, BiConsumer<BlockInProgress, Double> field) {
            this.label = label;
            this.low = low;
            this.high = high;
            this.field = field;
        }

        @Override
        protected boolean handles(Line line, BlockInProgress block) {
            return label.equals(line.label());
        }

        @Override
        protected Optional<Esp32Reading> process(Line line, BlockInProgress block) {
            field.accept(block, within(line.value()));
            return Optional.empty();
        }

        private Double within(String raw) {
            Matcher number = NUMBER.matcher(raw);
            if (!number.matches()) {
                return null;  // nan, inf, empty or unreadable: not sent
            }
            double value = Double.parseDouble(number.group(1).replace(',', '.'));
            if (value < low || value > high) {
                log.warn("ESP32: {} = {} is outside {} to {}, left out", label, value, low, high);
                return null;
            }
            return value;
        }
    }

    public static final class Ignored extends LineHandler {

        @Override
        protected boolean handles(Line line, BlockInProgress block) {
            return true;
        }

        @Override
        protected Optional<Esp32Reading> process(Line line, BlockInProgress block) {
            return Optional.empty();
        }
    }
}
