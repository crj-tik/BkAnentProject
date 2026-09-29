package com.bkanent.interview.engine;

/**
 * 提纲路由：开台要素 → T1–T8 路由 + 禁成功预设判定。
 *
 * <p>纯函数，零 Spring 依赖。路由规则源自 S²访谈台的场景-提纲映射：
 * T1 经纪人复盘、T2.1/T2.2 业主、T3.1/T3.2 客户、T4 开发商营销总、
 * T5 案场OP、T6 店东店总、T7 置业顾问、T8 社区专家。</p>
 */
public final class OutlineRouter {

    private OutlineRouter() {
    }

    /** 提纲路由结果。 */
    public record RouteResult(String outlineRoute, boolean noSuccessPreset, java.util.List<String> mustCollectItems) {
    }

    /**
     * 按场景与受访人角色路由提纲。
     *
     * @param scene           场景（STORE_MANAGER|AGENT_DEAL|SECOND_HAND_PARTY|NEW_HOUSE_FIELD|COMMUNITY_EXPERT）
     * @param respondentRole  受访人角色
     * @param caseStatus      案例状态（won|active|lost|churned）
     */
    public static RouteResult route(String scene, String respondentRole, String caseStatus) {
        boolean noSuccess = noSuccessPreset(scene, caseStatus);
        java.util.List<String> mustCollect = noSuccess
                ? java.util.List.of("真实卡点", "竞品胜出原因", "决策停滞点")
                : java.util.List.of();

        String outline = switch (scene == null ? "" : scene) {
            case "STORE_MANAGER" -> "T6";
            case "AGENT_DEAL" -> "T1";
            case "SECOND_HAND_PARTY" -> routeSecondHand(respondentRole);
            case "NEW_HOUSE_FIELD" -> routeNewHouse(respondentRole);
            case "COMMUNITY_EXPERT" -> "T8";
            default -> throw new IllegalArgumentException("unknown interview scene: " + scene);
        };
        return new RouteResult(outline, noSuccess, mustCollect);
    }

    /** 未成交/流失案例自动启用禁成功预设（消费者场景）。 */
    public static boolean noSuccessPreset(String scene, String caseStatus) {
        if (!"SECOND_HAND_PARTY".equals(scene) && !"AGENT_DEAL".equals(scene)) {
            return false;
        }
        return "lost".equals(caseStatus) || "churned".equals(caseStatus);
    }

    private static String routeSecondHand(String role) {
        if (role == null) {
            throw new IllegalArgumentException("respondentRole is required for SECOND_HAND_PARTY");
        }
        return switch (role) {
            case "OWNER" -> "T2.1";
            case "OWNER_REVISIT" -> "T2.1";
            case "CUSTOMER" -> "T3.1";
            case "CUSTOMER_DECIDING" -> "T3.2";
            default -> throw new IllegalArgumentException("unknown respondent role for SECOND_HAND_PARTY: " + role);
        };
    }

    private static String routeNewHouse(String role) {
        if (role == null) {
            throw new IllegalArgumentException("respondentRole is required for NEW_HOUSE_FIELD");
        }
        return switch (role) {
            case "MARKETING_DIRECTOR" -> "T4";
            case "FIELD_OP" -> "T5";
            case "CONSULTANT" -> "T7";
            case "CUSTOMER" -> "T3.2";
            default -> throw new IllegalArgumentException("unknown respondent role for NEW_HOUSE_FIELD: " + role);
        };
    }
}
