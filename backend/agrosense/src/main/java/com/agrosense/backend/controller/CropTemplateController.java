package com.agrosense.backend.controller;

import com.agrosense.backend.pattern.creational.prototype.CropTemplate;
import com.agrosense.backend.pattern.creational.prototype.CropTemplateCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class CropTemplateController {

    private final CropTemplateCatalog catalog;

    public record TemplateResponse(String key, String label, BigDecimal humidityMin, BigDecimal humidityMax,
            BigDecimal tempMin, BigDecimal tempMax, BigDecimal phMin, BigDecimal phMax) {

        static TemplateResponse from(CropTemplate template) {
            return new TemplateResponse(template.getKey(), template.getLabel(), template.getHumidity().getMin(),
                    template.getHumidity().getMax(), template.getTemperature().getMin(),
                    template.getTemperature().getMax(), template.getPh().getMin(), template.getPh().getMax());
        }
    }

    @GetMapping("/api/crop-templates")
    public List<TemplateResponse> templates() {
        return catalog.all().stream().map(TemplateResponse::from).toList();
    }
}
