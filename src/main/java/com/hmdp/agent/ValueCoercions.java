package com.hmdp.agent;

/**
 * Narrowing rules for the agent-core records, which are narrower than the DB-backed entities.
 */
final class ValueCoercions {

    private ValueCoercions() {
    }

    /**
     * Narrows a {@code Long} money value to {@code Integer}, saturating instead of wrapping.
     *
     * @param value the value, may be {@code null}
     * @return the narrowed value, or {@code null} when the input was {@code null}
     */
    static Integer clampToInt(Long value) {
        if (value == null) {
            return null;
        }
        return clampToInt(value.longValue());
    }

    /**
     * Narrows a {@code long} to {@code Integer}, saturating at {@link Integer#MIN_VALUE} /
     * {@link Integer#MAX_VALUE} so an out-of-range value never wraps into a nonsense price.
     */
    static Integer clampToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /**
     * Converts {@code Shop.score} to the rating the tools expose.
     *
     * <p>The entity stores the rating multiplied by ten to avoid decimals ("评分，1~5分，乘10保存"),
     * so 47 becomes 4.7.</p>
     *
     * @param score the stored value, may be {@code null}
     * @return the rating in 1..5, or {@code null} when unknown
     */
    static Double scoreFromTenths(Integer score) {
        return score == null ? null : score / 10.0;
    }
}
