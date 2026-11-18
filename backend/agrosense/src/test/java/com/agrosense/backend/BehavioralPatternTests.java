package com.agrosense.backend;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.pattern.behavioral.chain.BlockInProgress;
import com.agrosense.backend.pattern.behavioral.chain.Esp32LineHandlers;
import com.agrosense.backend.pattern.behavioral.chain.Line;
import com.agrosense.backend.pattern.behavioral.chain.LineHandler;
import com.agrosense.backend.pattern.behavioral.command.FieldWatchCommand;
import com.agrosense.backend.pattern.behavioral.command.FieldWatchInvoker;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.And;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleExpression.Or;
import com.agrosense.backend.pattern.behavioral.interpreter.RuleParser;
import com.agrosense.backend.pattern.behavioral.iterator.BlockWindow;
import com.agrosense.backend.pattern.behavioral.iterator.FieldIterator;
import com.agrosense.backend.pattern.behavioral.observer.Esp32Feed;
import com.agrosense.backend.pattern.behavioral.observer.Esp32FeedObserver;
import com.agrosense.backend.pattern.behavioral.state.Esp32Link;
import com.agrosense.backend.pattern.behavioral.state.Esp32LinkState;
import com.agrosense.backend.pattern.behavioral.strategy.AggregationStrategy;
import com.agrosense.backend.pattern.behavioral.strategy.MeanAggregation;
import com.agrosense.backend.pattern.behavioral.strategy.MedianAggregation;
import com.agrosense.backend.pattern.behavioral.templatemethod.ScheduledWatch;
import com.agrosense.backend.pattern.behavioral.visitor.EventDataWriter;
import com.agrosense.backend.pattern.behavioral.visitor.MeasureDescriber;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The behavioural patterns of docs/PATRONES.md, each on the part of the system it serves. */
class BehavioralPatternTests {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {

        private Instant now = Instant.parse("2026-11-18T15:00:00Z");

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // Chain of Responsibility

    @Test
    void eachLineIsTakenByTheFirstHandlerThatRecognisesItAndNoOtherSeesIt() {
        LineHandler chain = Esp32LineHandlers.chain(256, -20, 80);
        BlockInProgress block = new BlockInProgress();

        assertThat(handle(chain, block, "Humedad del suelo: 50 %")).as("before any header").isEmpty();
        assertThat(handle(chain, block, "===== PLANTICAS =====")).isEmpty();
        assertThat(handle(chain, block, "Temperatura exterior: 24,5 C")).isEmpty();
        assertThat(handle(chain, block, "Bateria: 3.7 V")).as("taken by the last link, which ignores it").isEmpty();
        assertThat(handle(chain, block, "HUMEDAD DEL SUELO: 31 %")).isEmpty();
        assertThat(handle(chain, block, "")).contains(new Esp32Reading(24.5, null, 31.0));

        // A header closes the block in progress: the next link never sees it as a field.
        handle(chain, block, "===== PLANTICAS =====");
        handle(chain, block, "Humedad relativa: 58 %");
        assertThat(handle(chain, block, "== PLANTICAS ==")).contains(new Esp32Reading(null, 58.0, null));
        assertThat(handle(chain, block, "Humedad relativa: " + "9".repeat(300))).as("too long: skipped whole")
                .isEmpty();
        assertThat(block.close()).as("the long line set nothing").isEmpty();
        assertThat(block.discarded()).isEqualTo(1);
    }

    private static Optional<Esp32Reading> handle(LineHandler chain, BlockInProgress block, String line) {
        return chain.handle(Line.of(line), block);
    }

    // Visitor

    @Test
    void visitorsWriteTheEventAndDescribeTheReadingWithoutAskingWhichMeasureEachOneIs() {
        Esp32Reading reading = new Esp32Reading(23.4, null, 32.45);
        assertThat(reading.measures()).hasSize(2);

        ObjectNode data = JsonMapper.builder().build().createObjectNode();
        EventDataWriter writer = new EventDataWriter(data);
        reading.measures().forEach(measure -> measure.accept(writer));
        assertThat(data.toString()).isEqualTo("{\"air_temperature\":23.4,\"soil_moisture\":32.45}");

        assertThat(reading.measures().stream().map(measure -> measure.accept(new MeasureDescriber())).toList())
                .containsExactly("temperatura 23,4 °C", "suelo 32,5 %");
    }

    // Iterator

    @Test
    void theWindowIsWalkedOneFieldAtATimeSkippingTheBlocksThatDidNotMeasureIt() {
        BlockWindow window = new BlockWindow();
        for (int i = 0; i < 100; i++) {
            window.add(new Esp32Reading(null, i % 2 == 0 ? null : (double) i, 40.0));
        }
        assertThat(window.size()).as("grows past its first 64 places").isEqualTo(100);

        FieldIterator humidity = window.values(Esp32Reading::relativeHumidity);
        List<Double> seen = new ArrayList<>();
        humidity.forEachRemaining(seen::add);
        assertThat(seen).hasSize(50).startsWith(1.0, 3.0).endsWith(99.0);
        assertThatThrownBy(humidity::next).isInstanceOf(NoSuchElementException.class);
        assertThat(window.values(Esp32Reading::airTemperature).hasNext()).isFalse();

        window.clear();
        assertThat(window.iterator().hasNext()).isFalse();
    }

    // Strategy

    @Test
    void theAggregatorCombinesTheWindowWithTheStrategyItIsGiven() {
        List<Double> withASpike = List.of(50.0, 51.0, 99.0);
        assertThat(new MeanAggregation().combine(withASpike.iterator())).isEqualTo(66.67);
        assertThat(new MedianAggregation().combine(withASpike.iterator())).isEqualTo(51.0);
        assertThat(new MedianAggregation().combine(List.of(40.0, 50.0).iterator())).isEqualTo(45.0);
        assertThat(new MedianAggregation().combine(List.<Double>of().iterator())).isNull();

        assertThat(AggregationStrategy.named("mediana")).isInstanceOf(MedianAggregation.class);
        assertThat(AggregationStrategy.named(null)).isInstanceOf(MeanAggregation.class);
        assertThatThrownBy(() -> AggregationStrategy.named("moda")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESP32_AGGREGATE_STRATEGY");

        ManualClock clock = new ManualClock();
        Esp32ReadingAggregator median = new Esp32ReadingAggregator(Duration.ofSeconds(60), clock,
                new MedianAggregation());
        withASpike.forEach(soil -> median.add(new Esp32Reading(null, null, soil)));
        assertThat(median.flush()).get().extracting(window -> window.average().soilMoisture()).isEqualTo(51.0);
    }

    // Observer

    @Test
    void theFeedTellsEveryObserverEvenWhenOneOfThemFails() {
        Esp32Feed feed = new Esp32Feed();
        AtomicInteger told = new AtomicInteger();
        Esp32FeedObserver failing = new Esp32FeedObserver() {
            @Override
            public void blockReceived(Esp32Reading block, Instant at) {
                throw new IllegalStateException("broken observer");
            }
        };
        Esp32FeedObserver counting = new Esp32FeedObserver() {
            @Override
            public void blockReceived(Esp32Reading block, Instant at) {
                told.incrementAndGet();
            }
        };
        feed.subscribe(failing);
        feed.subscribe(counting);

        feed.blockReceived(new Esp32Reading(20.0, null, null), Instant.now());
        assertThat(told).hasValue(1);

        feed.unsubscribe(counting);
        feed.blockReceived(new Esp32Reading(20.0, null, null), Instant.now());
        assertThat(told).as("an observer that left is not told").hasValue(1);
    }

    // State

    @Test
    void theLinkMovesBetweenItsStatesAsTheFeedAndTheTimeSay() {
        ManualClock clock = new ManualClock();
        Esp32Link link = new Esp32Link(clock, Duration.ofSeconds(5));
        assertThat(link.state()).isEqualTo(Esp32LinkState.DISCONNECTED);

        link.connectionOpened(clock.instant());
        assertThat(link.state()).isEqualTo(Esp32LinkState.WAITING);
        clock.advance(Duration.ofMinutes(1));
        assertThat(link.state()).as("waiting does not fall silent: it never received").isEqualTo(Esp32LinkState.WAITING);

        link.blockReceived(new Esp32Reading(20.0, null, null), clock.instant());
        assertThat(link.state()).isEqualTo(Esp32LinkState.RECEIVING);
        assertThat(link.label()).isEqualTo("Recibiendo datos del ESP32");

        clock.advance(Duration.ofSeconds(8));
        assertThat(link.state()).isEqualTo(Esp32LinkState.SILENT);
        assertThat(link.label()).isEqualTo("Conectado, pero el ESP32 no envía datos desde hace 8 s");

        link.blockReceived(new Esp32Reading(20.0, null, null), clock.instant());
        assertThat(link.state()).isEqualTo(Esp32LinkState.RECEIVING);
        link.connectionOpened(clock.instant());
        assertThat(link.state()).as("a second open changes nothing").isEqualTo(Esp32LinkState.RECEIVING);

        link.connectionLost(clock.instant());
        assertThat(link.state()).isEqualTo(Esp32LinkState.DISCONNECTED);
        assertThat(link.label()).isEqualTo("Sin conexión con el ESP32; se reintenta solo");
    }

    // Mediator, with the Observer, the State and the Visitor behind it

    @Test
    void theServiceMediatesBetweenTheLinesTheWindowAndTheIngestion() {
        DeviceIngestionFacade ingestion = mock(DeviceIngestionFacade.class);
        Esp32TcpIngestionService esp32 = new Esp32TcpIngestionService(ingestion, false, "192.168.4.1", 8080,
                "ESP32-PLANTICAS", 60, 5000, 30000, 2000, 30000, false, "median");

        esp32.connectionOpened();
        assertThat(esp32.status().link()).isEqualTo("WAITING");
        for (String soil : List.of("30", "31", "90")) {
            Arrays.asList("===== PLANTICAS =====", "Temperatura exterior: 24.00 C", "Humedad relativa: nan %",
                    "Humedad del suelo: " + soil + " %", "").forEach(esp32::lineArrived);
        }
        assertThat(esp32.status().receivedBlocks()).isEqualTo(3);
        assertThat(esp32.status().link()).isEqualTo("RECEIVING");

        esp32.connectionLost();  // the window is flushed and stored
        ArgumentCaptor<DeviceEventRequest> event = ArgumentCaptor.forClass(DeviceEventRequest.class);
        verify(ingestion, times(1)).ingestFromConfiguredDevice(event.capture());
        assertThat(event.getValue().getData().toString())
                .as("the median: the 90 % spike does not move it; the air humidity was never measured")
                .isEqualTo("{\"air_temperature\":24.0,\"soil_moisture\":31.0}");
        assertThat(esp32.status().storedReadings()).isEqualTo(1);
        assertThat(esp32.status().aggregation()).isEqualTo("median");
        assertThat(esp32.status().link()).isEqualTo("DISCONNECTED");
    }

    // Interpreter

    @Test
    void aWateringRuleIsReadIntoATreeThatJudgesTheFieldsConditions() {
        RuleExpression rule = RuleParser.parse("Suelo < 40 o aire <= 30,5 y temperatura > 25");
        assertThat(rule).as("y binds tighter than o").isInstanceOf(Or.class);
        assertThat(((Or) rule).right()).isInstanceOf(And.class);
        assertThat(rule.text()).isEqualTo("suelo < 40 o aire <= 30.5 y temperatura > 25");

        assertThat(rule.interpret(conditions(35, 80, 20))).as("dry soil").isTrue();
        assertThat(rule.interpret(conditions(55, 30, 28))).as("dry, hot air").isTrue();
        assertThat(rule.interpret(conditions(55, 30, 20))).isFalse();
        assertThat(rule.interpret(Map.of())).as("no data never recommends watering").isFalse();

        RuleExpression grouped = RuleParser.parse("(suelo < 40 o aire < 30) y temperatura >= 25");
        assertThat(grouped.text()).isEqualTo("(suelo < 40 o aire < 30) y temperatura >= 25");
        assertThat(grouped.interpret(conditions(35, 80, 20))).isFalse();
        assertThat(grouped.variables().stream().map(RuleExpression.Variable::word).collect(Collectors.joining(",")))
                .isEqualTo("suelo,temperatura,aire");

        assertThatThrownBy(() -> RuleParser.parse("lluvia < 3")).hasMessageContaining("no es una medida");
        assertThatThrownBy(() -> RuleParser.parse("suelo = 3")).hasMessageContaining("no se entiende");
        assertThatThrownBy(() -> RuleParser.parse("suelo < 40 y")).hasMessageContaining("termina antes de tiempo");
        assertThatThrownBy(() -> RuleParser.parse("(suelo < 40")).hasMessageContaining("termina antes de tiempo");
        assertThatThrownBy(() -> RuleParser.parse("suelo < 40 aire < 3")).hasMessageContaining("sobra desde «aire»");
        assertThatThrownBy(() -> RuleParser.parse(" ")).hasMessageContaining("vacía");
    }

    private static Map<SensorType, BigDecimal> conditions(double soil, double air, double temperature) {
        Map<SensorType, BigDecimal> conditions = new EnumMap<>(SensorType.class);
        conditions.put(SensorType.SOIL_MOISTURE, BigDecimal.valueOf(soil));
        conditions.put(SensorType.RELATIVE_HUMIDITY, BigDecimal.valueOf(air));
        conditions.put(SensorType.AIR_TEMPERATURE, BigDecimal.valueOf(temperature));
        return conditions;
    }

    // Command and Template Method

    @Test
    void theInvokerRunsAnyWatchAndRemembersTheLastRunsNewestFirst() {
        FieldWatchInvoker invoker = new FieldWatchInvoker(new ManualClock());
        AtomicInteger runs = new AtomicInteger();
        FieldWatchCommand counting = new FieldWatchCommand() {
            @Override
            public String name() {
                return "counting";
            }

            @Override
            public void execute() {
                runs.incrementAndGet();
            }
        };
        for (int i = 0; i < FieldWatchInvoker.HISTORY + 5; i++) {
            invoker.run(counting);
        }
        assertThat(runs).hasValue(FieldWatchInvoker.HISTORY + 5);
        assertThat(invoker.history()).hasSize(FieldWatchInvoker.HISTORY)
                .allSatisfy(run -> assertThat(run.command()).isEqualTo("counting"));
    }

    @Test
    void everyWatchFollowsTheSameStepsAndAFailureNeverReachesTheScheduler() {
        List<String> steps = new ArrayList<>();
        class Watch extends ScheduledWatch {
            private final boolean enabled;
            private final boolean fails;

            Watch(boolean enabled, boolean fails) {
                this.enabled = enabled;
                this.fails = fails;
            }

            @Override
            public String name() {
                return "test watch";
            }

            @Override
            protected boolean isEnabled() {
                return enabled;
            }

            @Override
            protected void watch() {
                steps.add("watched");
                if (fails) {
                    throw new IllegalStateException("the weather service is down");
                }
            }
        }
        new Watch(false, false).execute();
        assertThat(steps).as("off: the step is skipped").isEmpty();
        new Watch(true, false).execute();
        new Watch(true, true).execute();
        assertThat(steps).containsExactly("watched", "watched");
    }
}
