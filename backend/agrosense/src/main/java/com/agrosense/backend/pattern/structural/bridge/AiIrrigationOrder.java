package com.agrosense.backend.pattern.structural.bridge;

import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.pattern.structural.proxy.AiServiceProxy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Nobody is watching, so the model has to earn it: a real model, sure of itself and asking for a sane duration. */
public class AiIrrigationOrder extends IrrigationOrder {

    public static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.70");
    public static final int MAX_MINUTES = 120;

    private final Crop crop;
    private final IrrigationPrediction prediction;

    public AiIrrigationOrder(ActuatorDriver driver, Crop crop, IrrigationPrediction prediction) {
        super(driver);
        this.crop = crop;
        this.prediction = prediction;
    }

    @Override
    protected boolean authorized() {
        Integer minutes = prediction.durationMinutes();
        return prediction.irrigate() && minutes != null && minutes > 0 && minutes <= MAX_MINUTES
                && prediction.confidence() != null && prediction.confidence().compareTo(MIN_CONFIDENCE) >= 0
                && !AiServiceProxy.LOCAL_MODEL.equals(prediction.modelUsed());
    }

    @Override
    protected Irrigation record() {
        LocalDateTime startedAt = LocalDateTime.now();
        int minutes = runnable(prediction.durationMinutes());
        return Irrigation.builder()
                .crop(crop)
                .startedAt(startedAt)
                .endedAt(startedAt.plusMinutes(minutes))
                .durationMin(minutes)
                .type(IrrigationType.AUTOMATIC)
                .reason("Predicción de " + prediction.modelUsed())
                .build();
    }

    @Override
    protected IrrigationCommand command(Irrigation stored) {
        return new IrrigationCommand(crop.getIdCrop(), stored.getDurationMin(), IrrigationCommand.AI,
                prediction.confidence(), stored.getIdIrrigation());
    }
}
