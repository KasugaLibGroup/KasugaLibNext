package lib.kasuga.create;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateWeatherConfigTest {
    private static final String KEY = "enable_contraption_weather_filter";

    @Test
    void customDefaultIsUsedOnlyForMissingValues() {
        boolean previousDefault = KasugaLibCreateApi.getContraptionWeatherFilterDefault();
        try {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
            new CreateConfig().configureCommon(builder);
            ModConfigSpec spec = builder.build();

            KasugaLibCreateApi.setContraptionWeatherFilterDefault(false);
            CommentedConfig generated = CommentedConfig.inMemory();
            spec.correct(generated);
            assertEquals(Boolean.FALSE, generated.get(KEY));

            generated.set(KEY, true);
            spec.correct(generated);
            assertEquals(Boolean.TRUE, generated.get(KEY));

            KasugaLibCreateApi.setContraptionWeatherFilterDefault(true);
            generated.set(KEY, false);
            spec.correct(generated);
            assertEquals(Boolean.FALSE, generated.get(KEY));
        } finally {
            KasugaLibCreateApi.setContraptionWeatherFilterDefault(previousDefault);
        }
    }
}
