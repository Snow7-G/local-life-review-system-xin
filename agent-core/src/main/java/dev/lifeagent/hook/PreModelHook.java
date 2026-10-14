package dev.lifeagent.hook;

/**
 * Runs immediately before the model is called. Blocking here means the model is never called for
 * this step at all (the injection-detection guard of W5 plugs in here).
 */
@FunctionalInterface
public interface PreModelHook extends Hook {

    /**
     * @param context the current step
     * @return pass, pass-with-note, or block
     */
    HookResult beforeModel(TurnContext context);

    @Override
    default String name() {
        return getClass().getSimpleName();
    }
}
