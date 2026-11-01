package com.agrosense.backend.pattern.creational.prototype;

import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.pattern.creational.prototype.CropTemplate.Range;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reference ranges to start from; each farm then adjusts them to its own soil and variety. */
@Component
public class CropTemplateCatalog {

    private final Map<String, CropTemplate> templates = List.of(
            template("CAFE", "60", "80", "18", "24", "5.0", "6.5"),
            template("CACAO", "60", "85", "21", "32", "5.0", "7.0"),
            template("AGUACATE", "50", "75", "16", "28", "5.5", "7.0"),
            template("MAIZ", "50", "80", "18", "30", "5.5", "7.5"))
            .stream().collect(Collectors.toUnmodifiableMap(CropTemplate::getKey, Function.identity()));

    public CropTemplate cloneOf(String key) {
        CropTemplate original = templates.get(key.trim().toUpperCase(Locale.ROOT));
        if (original == null) {
            throw new BusinessRuleException("Plantilla de cultivo desconocida. Usa una de: "
                    + templates.keySet().stream().sorted().toList() + ".");
        }
        return original.clone();
    }

    private static CropTemplate template(String key, String humidityMin, String humidityMax, String tempMin,
            String tempMax, String phMin, String phMax) {
        return new CropTemplate(key, range(humidityMin, humidityMax), range(tempMin, tempMax), range(phMin, phMax));
    }

    private static Range range(String min, String max) {
        return new Range(new BigDecimal(min), new BigDecimal(max));
    }
}
