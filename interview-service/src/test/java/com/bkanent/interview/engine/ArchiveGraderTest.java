package com.bkanent.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveGraderTest {

    @Test
    void shortBodyRejectedAsL0() {
        ArchiveGrader.GradeResult r = ArchiveGrader.grade(200, true, true, true, true, 10, true);
        assertEquals("L0", r.grade());
        assertEquals(6, r.checks().size());
    }

    @Test
    void partialPassIsL1() {
        ArchiveGrader.GradeResult r = ArchiveGrader.grade(500, true, false, false, false, 2, true);
        assertEquals("L1", r.grade());
    }

    @Test
    void allPassIsL2() {
        ArchiveGrader.GradeResult r = ArchiveGrader.grade(900, true, true, true, true, 6, true);
        assertEquals("L2", r.grade());
    }

    @Test
    void exemplaryArchiveIsL3() {
        ArchiveGrader.GradeResult r = ArchiveGrader.grade(1800, true, true, true, true, 12, true);
        assertEquals("L3", r.grade());
    }

    @Test
    void missingMetadataForcesDowngrade() {
        ArchiveGrader.GradeResult r = ArchiveGrader.grade(900, true, true, true, true, 6, false);
        assertEquals("L1", r.grade());
    }

    @Test
    void regradeAfterMetadataFilled() {
        ArchiveGrader.GradeResult before = ArchiveGrader.grade(900, true, true, true, true, 6, false);
        ArchiveGrader.GradeResult after = ArchiveGrader.grade(900, true, true, true, true, 6, true);
        assertEquals("L1", before.grade());
        assertEquals("L2", after.grade());
    }

    @Test
    void onlyL2AndL3PubliclyVisible() {
        assertTrue(ArchiveGrader.isPubliclyVisible("L2"));
        assertTrue(ArchiveGrader.isPubliclyVisible("L3"));
        assertFalse(ArchiveGrader.isPubliclyVisible("L0"));
        assertFalse(ArchiveGrader.isPubliclyVisible("L1"));
    }

    @Test
    void gradeBasisJsonContainsChecks() {
        String json = ArchiveGrader.gradeBasisJson(ArchiveGrader.grade(900, true, true, true, true, 6, true));
        assertTrue(json.contains("\"grade\":\"L2\""));
        assertTrue(json.contains("body_length"));
        assertTrue(json.contains("turn_count"));
    }
}
