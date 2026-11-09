/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.laya;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The standard System 1 question set, matching the schema in AGENTS.md §4.2.
 *
 * <p>Laya answers all three in one forward pass (~30 ms): the atomic action,
 * a danger score, and whether the macro plan needs the System 2 planner again.
 * Keeping the schema in one place means the prompt the model sees and the
 * answers the controller parses can never drift apart.
 */
public final class LayaProtocol {

    /** Action ids the controller knows how to execute. */
    public static final String ACTION_MINE = "mine";
    public static final String ACTION_EXPLORE = "explore";
    public static final String ACTION_COMBAT = "combat";
    public static final String ACTION_FLEE = "flee";
    public static final String ACTION_EAT = "eat";
    public static final String ACTION_ADVANCE = "advance";

    /** Danger levels (score criteria order = index). */
    public static final List<String> THREAT_LEVELS = List.of("safe", "caution", "danger", "critical");

    private LayaProtocol() {}

    /**
     * Build the decision questions for the current macro step.
     *
     * <p>The instruction is deliberately the canonical short form: the classifier is sensitive to
     * long, multi-part instructions (measured: confidences drop from ~0.85 to ~0.1). All grounding
     * belongs in the state string.
     */
    public static Map<String, LayaClient.Question> microQuestions() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put(ACTION_MINE, "continue gathering or mining the target");
        criteria.put(ACTION_EXPLORE, "move outward to search for the target");
        criteria.put(ACTION_COMBAT, "eliminate an urgent threatening hostile");
        criteria.put(ACTION_FLEE, "retreat due to low health or an overwhelming swarm");
        criteria.put(ACTION_EAT, "replenish hunger or health");
        criteria.put(ACTION_ADVANCE, "the sub-goal target is already met");

        Map<String, LayaClient.Question> questions = new LinkedHashMap<>();
        questions.put("action", LayaClient.Question.choice(
                "What immediate action should the bot take?", criteria));
        questions.put("threat_level", LayaClient.Question.score(
                "Rate the danger level of the current situation", THREAT_LEVELS));
        questions.put("need_replan", LayaClient.Question.noul(
                "Is the bot stuck or unable to find the required block?"));
        return questions;
    }

    /** Human-readable name for a threat score (index into {@link #THREAT_LEVELS}). */
    public static String threatName(Integer score) {
        if (score == null) return "unknown";
        int i = Math.max(0, Math.min(THREAT_LEVELS.size() - 1, score));
        return THREAT_LEVELS.get(i);
    }
}
