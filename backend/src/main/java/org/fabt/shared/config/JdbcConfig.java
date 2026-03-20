package org.fabt.shared.config;

import java.sql.SQLException;
import java.util.List;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

@Configuration
public class JdbcConfig extends AbstractJdbcConfiguration {

    @Override
    protected List<?> userConverters() {
        return List.of(new JsonStringToJsonbConverter(), new PGobjectToJsonStringConverter());
    }

    /**
     * Converts JsonString wrapper to PGobject(jsonb) for JSONB column writes.
     */
    @WritingConverter
    static class JsonStringToJsonbConverter implements Converter<JsonString, PGobject> {
        @Override
        public PGobject convert(JsonString source) {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            try {
                pgObject.setValue(source.value());
            } catch (SQLException e) {
                throw new IllegalArgumentException("Failed to convert to JSONB", e);
            }
            return pgObject;
        }
    }

    /**
     * Converts PGobject back to JsonString for JSONB column reads.
     */
    @ReadingConverter
    static class PGobjectToJsonStringConverter implements Converter<PGobject, JsonString> {
        @Override
        public JsonString convert(PGobject source) {
            return new JsonString(source.getValue());
        }
    }
}
