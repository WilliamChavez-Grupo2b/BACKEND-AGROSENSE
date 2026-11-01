package com.agrosense.backend.pattern.structural.bridge;

import com.agrosense.backend.domain.enums.DispatchStatus;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.IrrigationDispatch;
import com.agrosense.backend.repository.IrrigationDispatchRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Orders waiting for their device, one per crop: the newest replaces an older one. Each is stored, so its whole life
 * stays on record. It expires because water started long after someone asked for it is worse than no water. What the
 * device reports corrects the irrigation in the history: it says what ran, not what was asked for.
 */
@Component
public class PendingIrrigationCommands {

    public static final Duration EXPIRES_AFTER = Duration.ofMinutes(5);
    public static final String STARTED = "started";
    public static final String FINISHED = "finished";
    public static final String REFUSED = "refused";

    private static final int MAX_REASON = 255;

    private final IrrigationDispatchRepository dispatches;
    private final IrrigationRepository irrigations;
    private final Clock clock;

    @Autowired
    public PendingIrrigationCommands(IrrigationDispatchRepository dispatches, IrrigationRepository irrigations) {
        this(dispatches, irrigations, Clock.systemDefaultZone());
    }

    public PendingIrrigationCommands(IrrigationDispatchRepository dispatches, IrrigationRepository irrigations,
            Clock clock) {
        this.dispatches = dispatches;
        this.irrigations = irrigations;
        this.clock = clock;
    }

    @Transactional
    public void offer(IrrigationCommand command) {
        LocalDateTime now = LocalDateTime.now(clock);
        dispatches.findByCropIdAndStatus(command.cropId(), DispatchStatus.PENDING)
                .forEach(older -> expire(older, "sustituido por una orden más reciente"));
        dispatches.save(IrrigationDispatch.builder()
                .cropId(command.cropId())
                .minutes(command.minutes())
                .source(command.source())
                .confidence(command.confidence())
                .irrigationId(command.irrigationId())
                .createdAt(now)
                .expiresAt(now.plus(EXPIRES_AFTER))
                .build());
    }

    @Transactional
    public Optional<IrrigationCommand> take(Integer cropId) {
        LocalDateTime now = LocalDateTime.now(clock);
        dispatches.findByCropIdAndStatus(cropId, DispatchStatus.PENDING).stream()
                .filter(dispatch -> !now.isBefore(dispatch.getExpiresAt()))
                .forEach(late -> expire(late, "caducó sin llegar al dispositivo"));
        return dispatches.findFirstByCropIdAndStatusOrderByIdDispatchDesc(cropId, DispatchStatus.PENDING)
                .filter(dispatch -> dispatches.claim(dispatch.getIdDispatch(), now) == 1)
                .map(dispatch -> new IrrigationCommand(dispatch.getIdDispatch(), cropId, dispatch.getMinutes(),
                        dispatch.getSource(), dispatch.getConfidence(), dispatch.getIrrigationId()));
    }

    /** What the device reports: started, then finished with the seconds it ran; or refused, which ran nothing. */
    @Transactional
    public boolean confirm(Integer cropId, Integer dispatchId, String result, Integer ranSeconds) {
        return dispatches.findById(dispatchId)
                .filter(dispatch -> dispatch.getCropId().equals(cropId))
                .filter(dispatch -> accepts(dispatch.getStatus(), result, ranSeconds))
                .map(dispatch -> {
                    dispatch.setConfirmedAt(LocalDateTime.now(clock));
                    switch (result) {
                        case STARTED -> dispatch.setStatus(DispatchStatus.STARTED);
                        case FINISHED -> {
                            dispatch.setStatus(DispatchStatus.FINISHED);
                            dispatch.setRanSeconds(ranSeconds);
                            irrigationOf(dispatch).ifPresent(irrigation -> recordWhatRan(irrigation, ranSeconds));
                        }
                        default -> {
                            dispatch.setStatus(DispatchStatus.REFUSED);
                            irrigationOf(dispatch).ifPresent(irrigation ->
                                    recordNothingRan(irrigation, "rechazado por el dispositivo"));
                        }
                    }
                    return true;
                })
                .orElse(false);
    }

    private static boolean accepts(DispatchStatus status, String result, Integer ranSeconds) {
        return switch (result) {
            case STARTED, REFUSED -> status == DispatchStatus.DELIVERED;
            case FINISHED -> (status == DispatchStatus.DELIVERED || status == DispatchStatus.STARTED)
                    && ranSeconds != null && ranSeconds >= 0;
            default -> false;
        };
    }

    private void expire(IrrigationDispatch dispatch, String why) {
        dispatch.setStatus(DispatchStatus.EXPIRED);
        irrigationOf(dispatch).ifPresent(irrigation -> recordNothingRan(irrigation, why));
    }

    private Optional<Irrigation> irrigationOf(IrrigationDispatch dispatch) {
        return dispatch.getIrrigationId() == null ? Optional.empty() : irrigations.findById(dispatch.getIrrigationId());
    }

    private static void recordWhatRan(Irrigation irrigation, int ranSeconds) {
        if (ranSeconds == 0) {
            recordNothingRan(irrigation, "no llegó a funcionar");
            return;
        }
        int planned = irrigation.getDurationMin() * 60;
        if (irrigation.getWaterLiters() != null && ranSeconds < planned) {
            irrigation.setWaterLiters(irrigation.getWaterLiters().multiply(BigDecimal.valueOf(ranSeconds))
                    .divide(BigDecimal.valueOf(planned), 2, RoundingMode.HALF_UP));
        }
        irrigation.setEndedAt(irrigation.getStartedAt().plusSeconds(ranSeconds));
        irrigation.setDurationMin(Math.max(1, (ranSeconds + 59) / 60));
    }

    private static void recordNothingRan(Irrigation irrigation, String why) {
        irrigation.setEndedAt(irrigation.getStartedAt());
        irrigation.setDurationMin(null);
        irrigation.setMoistureBefore(null);
        if (irrigation.getWaterLiters() != null) {
            irrigation.setWaterLiters(BigDecimal.ZERO);
        }
        String reason = (irrigation.getReason() == null ? "" : irrigation.getReason() + " · ") + why;
        irrigation.setReason(reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason);
    }
}
