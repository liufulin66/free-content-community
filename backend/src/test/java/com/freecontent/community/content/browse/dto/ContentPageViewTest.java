package com.freecontent.community.content.browse.dto;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentPageViewTest {
    @Test void aTerminalPageRequiresNullRatherThanAnEmptyCursor() {
        assertThrows(IllegalArgumentException.class, () -> new ContentPageView(List.of(), "", false, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new ContentPageView(List.of(), "cursor", false, Instant.EPOCH));
        assertDoesNotThrow(() -> new ContentPageView(List.of(), null, false, Instant.EPOCH));
    }
    @Test void aContinuingPageRequiresANonemptyCursorEvenWhenTheBatchIsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new ContentPageView(List.of(), null, true, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new ContentPageView(List.of(), "", true, Instant.EPOCH));
        assertDoesNotThrow(() -> new ContentPageView(List.of(), "cursor", true, Instant.EPOCH));
    }
}
