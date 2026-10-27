package support;

import org.testng.Assert;

import java.util.function.BooleanSupplier;

/** Waits up to 10 seconds for a condition that a background thread makes true. */
public final class Polling {
    private Polling() { }

    public static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        Assert.assertTrue(condition.getAsBoolean(), "the expected state did not arrive");
    }
}
