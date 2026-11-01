package com.agrosense.backend.pattern.structural.bridge;

import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A person decided: Bean Validation already checked the request, so nothing can refuse it here. */
public class ManualIrrigationOrder extends IrrigationOrder {

    private final Crop crop;
    private final User user;
    private final int minutes;
    private final BigDecimal liters;
    private final String reason;

    public ManualIrrigationOrder(ActuatorDriver driver, Crop crop, User user, int minutes, BigDecimal liters,
            String reason) {
        super(driver);
        this.crop = crop;
        this.user = user;
        this.minutes = minutes;
        this.liters = liters;
        this.reason = reason;
    }

    @Override
    protected boolean authorized() {
        return true;
    }

    @Override
    protected Irrigation record() {
        LocalDateTime startedAt = LocalDateTime.now();
        return Irrigation.builder()
                .crop(crop)
                .startedAt(startedAt)
                .endedAt(startedAt.plusMinutes(minutes))
                .durationMin(minutes)
                .waterLiters(liters)
                .type(IrrigationType.MANUAL)
                .reason(reason)
                .activatedBy(user)
                .build();
    }

    @Override
    protected IrrigationCommand command(Irrigation stored) {
        return new IrrigationCommand(crop.getIdCrop(), minutes, IrrigationCommand.MANUAL, null,
                stored.getIdIrrigation());
    }
}
