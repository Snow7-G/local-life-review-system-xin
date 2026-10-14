package dev.lifeagent.event;

import java.util.List;
import java.util.Objects;

/**
 * The model's plan for the turn: a short summary plus the steps it intends to take.
 *
 * @param summary one-line description of the plan, never blank
 * @param steps   the planned steps in order; may be empty when the model only gives a summary
 */
public record PlanEvent(String summary, List<String> steps) implements AgentEvent {

    public PlanEvent {
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        for (String step : steps) {
            Objects.requireNonNull(step, "steps element");
        }
    }
}
