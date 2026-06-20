package org.example.miniwsa.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a body Jackson can't read to the same {@link IngestError} shape the controller uses
 * (spec §8: deserialization <i>is</i> validation). A bad enum/timestamp is reported as an indexed
 * invalid event (which event, which field, allowed values); unparseable JSON has no index, so its
 * {@code invalidEvents} is empty and the reason is in {@code error}.
 */
@RestControllerAdvice(assignableTypes = {IngestController.class, AsyncIngestController.class})
public class IngestExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException ife) {
            int index = arrayIndexOf(ife);
            String message = fieldPath(ife) + ": invalid value '" + ife.getValue() + "'" + expected(ife);
            IngestError body = new IngestError("validation failed",
                    List.of(new IngestError.InvalidEvent(index, List.of(message))));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        String reason = (ex.getCause() instanceof JsonProcessingException jpe)
                ? "malformed JSON: " + jpe.getOriginalMessage()
                : "malformed request body";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new IngestError(reason, List.of()));
    }

    /** The batch index of the offending event (0 for a single event), from the Jackson path. */
    private int arrayIndexOf(InvalidFormatException ife) {
        return ife.getPath().stream()
                .map(ref -> ref.getIndex())
                .filter(i -> i >= 0)
                .findFirst()
                .orElse(0);
    }

    private String fieldPath(InvalidFormatException ife) {
        return ife.getPath().stream()
                .map(ref -> ref.getFieldName())
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
    }

    private String expected(InvalidFormatException ife) {
        Class<?> target = ife.getTargetType();
        if (target == null) {
            return "";
        }
        return target.isEnum()
                ? " (expected one of " + Arrays.toString(target.getEnumConstants()) + ")"
                : " (expected " + target.getSimpleName() + ")";
    }
}
