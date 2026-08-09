package dev.stacklight.examples;

import dev.stacklight.client.StacklightClient;
import dev.stacklight.client.StacklightStats;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CheckoutController {

    private final StacklightClient stacklight;

    CheckoutController(StacklightClient stacklight) {
        this.stacklight = stacklight;
    }

    /**
     * Fails the way real code fails: several frames down, wrapped on the way out.
     *
     * <p>Nothing here reports anything. The exception leaves the controller, the starter's
     * resolver sees it on the way past, and the application's own error response is
     * produced exactly as it would have been.
     */
    @GetMapping("/boom")
    public Map<String, String> boom() {
        return Map.of("total", String.valueOf(new CartService().total(null)));
    }

    /** The same failure, reported by hand instead of by the framework. */
    @GetMapping("/handled")
    public Map<String, String> handled() {
        try {
            new CartService().total(null);
            return Map.of("status", "no failure");
        } catch (RuntimeException e) {
            stacklight.capture(e);
            return Map.of("status", "failed and reported", "error", e.getClass().getSimpleName());
        }
    }

    /** What the client has done so far, so the demo can be checked without the dashboard. */
    @GetMapping("/stacklight/stats")
    public StacklightStats stats() {
        return stacklight.stats();
    }

    static class CartService {
        int total(String promoCode) {
            try {
                return applyDiscount(promoCode);
            } catch (RuntimeException e) {
                throw new IllegalStateException("could not price the cart", e);
            }
        }

        private int applyDiscount(String promoCode) {
            return 100 - promoCode.length();
        }
    }
}
