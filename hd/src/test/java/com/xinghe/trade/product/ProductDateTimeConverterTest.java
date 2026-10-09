package com.xinghe.trade.product;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDateTimeConverterTest {
    private final ProductDateTimeConverter converter = new ProductDateTimeConverter();

    @Test
    void readsLegacyDateOnlyValueAtStartOfDay() {
        assertThat(converter.read("2026-10-09"))
                .isEqualTo(LocalDate.of(2026, 10, 9).atStartOfDay());
    }

    @Test
    void readsDateTimeValueWithoutLosingTime() {
        LocalDateTime value = LocalDateTime.of(2026, 10, 9, 16, 39, 57);

        assertThat(converter.read("2026-10-09T16:39:57")).isEqualTo(value);
    }

    @Test
    void writesLocalDateTimeAsElasticsearchDateTime() {
        assertThat(converter.write(LocalDateTime.of(2026, 10, 9, 16, 39, 57)))
                .isEqualTo("2026-10-09T16:39:57");
    }
}