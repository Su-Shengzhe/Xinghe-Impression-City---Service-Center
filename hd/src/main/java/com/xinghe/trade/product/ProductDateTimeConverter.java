package com.xinghe.trade.product;

import org.springframework.data.elasticsearch.core.mapping.PropertyValueConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * Reads both date-only and date-time values written by Elasticsearch.
 *
 * <p>Older product documents contain {@code yyyy-MM-dd}, while newly written
 * documents use the full local date-time value from MySQL.</p>
 */
public class ProductDateTimeConverter implements PropertyValueConverter {
    @Override
    public Object write(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime.toString();
        if (value instanceof LocalDate date) return date.atStartOfDay().toString();
        if (value instanceof String string) return string;
        throw new IllegalArgumentException("商品更新时间必须是日期时间: " + value.getClass().getName());
    }

    @Override
    public Object read(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof LocalDate date) return date.atStartOfDay();
        if (value instanceof Date date) return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        if (!(value instanceof CharSequence)) {
            throw new IllegalArgumentException("无法读取商品更新时间: " + value.getClass().getName());
        }

        String text = value.toString();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(text).atStartOfDay();
            } catch (DateTimeParseException dateOnlyException) {
                try {
                    return OffsetDateTime.parse(text).toLocalDateTime();
                } catch (DateTimeParseException dateTimeException) {
                    throw new IllegalArgumentException("无法读取商品更新时间: " + text, dateTimeException);
                }
            }
        }
    }
}