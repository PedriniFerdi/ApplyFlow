package com.applyflow.dto.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class StrictDecimalStringDeserializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsDecimalStringsAndRejectsJsonNumbersForMoneyFields() throws Exception {
        String base = "{\"companyId\":1,\"positionTitle\":\"Engineer\",\"status\":\"BOOKMARKED\",\"sourceId\":1,\"workMode\":\"REMOTE\",\"technologyIds\":[],\"currency\":\"USD\",\"salaryPeriod\":\"YEARLY\",\"salaryMin\":";
        CreateJobApplicationRequest stringAmount = objectMapper.readValue(
                base + "\"99999999999999999.99\"}", CreateJobApplicationRequest.class);

        assertThat(stringAmount.salaryMin()).isEqualTo("99999999999999999.99");
        assertThatThrownBy(() -> objectMapper.readValue(base + "99999999999999999.99}", CreateJobApplicationRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
