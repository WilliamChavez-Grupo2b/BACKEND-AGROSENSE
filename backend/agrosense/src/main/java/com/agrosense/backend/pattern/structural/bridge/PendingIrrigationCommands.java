package com.agrosense.backend.pattern.structural.bridge;

import com.agrosense.backend.domain.enums.DispatchStatus;
import com.agrosense.backend.models.IrrigationDispatch;
import com.agrosense.backend.repository.IrrigationDispatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Orders waiting for their device, one per crop: the newest replaces an older one. Each is stored, so its whole life
 * stays on record. It expires because water started long after someone asked for it is worse than no water.
 */
@Component
public class PendingIrrigationCommands {

    public static final Duration EXPIRES_AFTER = Duration.ofMinutes(5);

    private final IrrigationDispatchRepository dispatches;
    private final Clock clock;

    @Autowired
    public PendingIrrigationCommands(IrrigationDispatchRepository dispatches) {
        this(dispatches, Clock.systemDefaultZone());
    }

    public PendingIrrigationCommands(IrrigationDispatchRepository dispatches, Clock clock) {
        this.dispatches = dispatches;
        this.clock = clock;
    }

    @Transactional
    public void offer(IrrigationCommand command) {
        LocalDateTime now = LocalDateTime.now(clock);
        dispatches.expire(command.cropId(), now.plus(EXPIRES_AFTER).plusSeconds(1));
        dispatches.save(IrrigationDispatch.builder()
                .cropId(command.cropId())
                .minutes(command.minutes())
                .source(command.source())
                .confidence(command.confidence())
                .createdAt(now)
                .expiresAt(now.plus(EXPIRES_AFTER))
                .build());
    }

    @Transactional
    public Optional<IrrigationCommand> take(Integer cropId) {
        LocalDateTime now = LocalDateTime.now(clock);
        dispatches.expire(cropId, now);
        return dispatches.findFirstByCropIdAndStatusOrderByIdDispatchDesc(cropId, DispatchStatus.PENDING)
                .filter(dispatch -> dispatches.claim(dispatch.getIdDispatch(), now) == 1)
                .map(dispatch -> new IrrigationCommand(dispatch.getIdDispatch(), cropId, dispatch.getMinutes(),
                        dispatch.getSource(), dispatch.getConfidence()));
    }

    @Transactional
    public boolean confirm(Integer cropId, Integer dispatchId, boolean started) {
        return dispatches.confirm(dispatchId, cropId, started ? DispatchStatus.STARTED : DispatchStatus.REFUSED,
                LocalDateTime.now(clock)) == 1;
    }
}
