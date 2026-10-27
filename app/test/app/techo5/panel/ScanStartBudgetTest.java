package app.techo5.panel;

/** Runs with a plain JDK: tests the actual budget used by scan startup and GATT retries. */
public final class ScanStartBudgetTest {
    public static void main(String[] args) {
        ScanStartBudget b = new ScanStartBudget();
        for (int i = 0; i < 5; i++) {
            if (b.delay(i * 100) != 0) throw new AssertionError("initial registration refused");
            b.started(i * 100);
        }
        if (b.delay(500) != 30_500) throw new AssertionError("retry storm was not delayed");
        try {
            b.started(500);
            throw new AssertionError("sixth registration allowed");
        } catch (IllegalStateException expected) { }
        if (b.delay(31_000) != 0) throw new AssertionError("expired registration not released");
        b.started(31_000);
        if (b.delay(31_050) != 50) throw new AssertionError("rolling window lost later starts");
        if (b.delay(20 * 60_000) != 0) throw new AssertionError("scan renewal blocked");
        System.out.println("Scan start budget tests passed");
    }
}
