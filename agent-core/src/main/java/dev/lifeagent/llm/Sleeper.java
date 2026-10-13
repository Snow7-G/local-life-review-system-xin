package dev.lifeagent.llm;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration);

    void sleep(Duration duration) throws InterruptedException;
}
