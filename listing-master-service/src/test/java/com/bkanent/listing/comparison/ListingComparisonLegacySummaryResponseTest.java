package com.bkanent.listing.comparison;

import com.bkanent.listing.comparison.model.ListingComparisonLegacySummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingComparisonLegacySummaryResponseTest {
    @Test
    void keepsTheAuthorizedCompatibilityResponseFieldNames() {
        JsonNode json = new ObjectMapper().valueToTree(
                new ListingComparisonLegacySummaryResponse(List.of(), "table", "conclusion"));

        assertEquals(3, json.size());
        assertTrue(json.has("listings"));
        assertTrue(json.has("comparisonTableMarkdown"));
        assertTrue(json.has("aiConclusion"));
    }
}
