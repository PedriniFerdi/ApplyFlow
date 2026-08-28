package com.applyflow.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.repository.AccountExportRepository;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AccountExportService {

    private final AccountExportRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AccountExportService(AccountExportRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public void write(Long ownerId, OutputStream output) throws IOException {
        String profile = repository.profile(ownerId);
        try (JsonGenerator json = mapper.getFactory().createGenerator(output)) {
            json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            json.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
            json.writeStartObject();
            json.writeNumberField("schemaVersion", 1);
            json.writeStringField("exportedAt", clock.instant().toString());
            json.writeFieldName("profile");
            json.writeRawValue(profile);
            json.flush();
            for (var section : AccountExportRepository.Section.values()) {
                json.writeArrayFieldStart(section.fieldName());
                repository.stream(section, ownerId, row -> {
                    try {
                        json.writeRawValue(row);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
                json.writeEndArray();
            }
            json.writeBooleanField("complete", true);
            json.writeEndObject();
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
