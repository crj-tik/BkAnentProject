package com.bkanent.interview.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutlineRouterTest {

    @Test
    void routesStoreManagerToT6() {
        OutlineRouter.RouteResult r = OutlineRouter.route("STORE_MANAGER", "STORE_OWNER", "active");
        assertEquals("T6", r.outlineRoute());
        assertFalse(r.noSuccessPreset());
    }

    @Test
    void routesAgentDealToT1() {
        assertEquals("T1", OutlineRouter.route("AGENT_DEAL", "AGENT", "won").outlineRoute());
    }

    @Test
    void routesSecondHandOwnerToT21() {
        assertEquals("T2.1", OutlineRouter.route("SECOND_HAND_PARTY", "OWNER", "won").outlineRoute());
    }

    @Test
    void routesSecondHandCustomerToT31() {
        assertEquals("T3.1", OutlineRouter.route("SECOND_HAND_PARTY", "CUSTOMER", "won").outlineRoute());
    }

    @Test
    void routesNewHouseFieldOpToT5() {
        assertEquals("T5", OutlineRouter.route("NEW_HOUSE_FIELD", "FIELD_OP", "active").outlineRoute());
    }

    @Test
    void routesCommunityExpertToT8() {
        assertEquals("T8", OutlineRouter.route("COMMUNITY_EXPERT", "EXPERT", "active").outlineRoute());
    }

    @Test
    void lostCaseEnablesNoSuccessPresetWithMustCollect() {
        OutlineRouter.RouteResult r = OutlineRouter.route("SECOND_HAND_PARTY", "CUSTOMER", "lost");
        assertTrue(r.noSuccessPreset());
        assertTrue(r.mustCollectItems().contains("真实卡点"));
        assertTrue(r.mustCollectItems().contains("竞品胜出原因"));
    }

    @Test
    void churnedCaseEnablesNoSuccessPreset() {
        assertTrue(OutlineRouter.noSuccessPreset("AGENT_DEAL", "churned"));
    }

    @Test
    void wonCaseHasNoPreset() {
        assertFalse(OutlineRouter.noSuccessPreset("SECOND_HAND_PARTY", "won"));
        assertFalse(OutlineRouter.noSuccessPreset("STORE_MANAGER", "lost"));
    }

    @Test
    void unknownSceneThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OutlineRouter.route("UNKNOWN", "AGENT", "won"));
    }
}
