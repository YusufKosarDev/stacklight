package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MessageNormalizerTests {

    private final MessageNormalizer normalizer = new MessageNormalizer();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "order 8f14e45f-ceea-467a-9a1e-6b19d2f0e1a1 not found | order <uuid> not found",
                "connection to 10.0.14.7:5432 refused                | connection to <ip> refused",
                "user alice@example.com is unknown                   | user <email> is unknown",
                "GET https://api.example.com/v2/carts failed         | GET <url> failed",
                "retry 3 of 5 after 250 ms                           | retry <num> of <num> after <num> ms",
                "expected at 2026-08-07T11:57:26Z                    | expected at <timestamp>",
                "object at 0x7ffee3b2c9a0 released                   | object at <hex> released",
                "checksum d41d8cd98f00b204e9800998ecf8427e mismatch  | checksum <hex> mismatch",
                "cannot read /var/lib/app/data/cache.db              | cannot read <path>",
            })
    void replacesVaryingParts(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void keepsIdentifiersIntactRatherThanShreddingThem() {
        // The digits inside a UUID must not be replaced before the UUID rule runs,
        // otherwise two messages differing only by identifier stop matching.
        String first = normalizer.normalize("cart 8f14e45f-ceea-467a-9a1e-6b19d2f0e1a1 is empty");
        String second = normalizer.normalize("cart 1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed is empty");

        assertThat(first).isEqualTo("cart <uuid> is empty").isEqualTo(second);
    }

    @Test
    void collapsesWhitespaceSoWrappingDoesNotMatter() {
        assertThat(normalizer.normalize("too   many\n\tspaces")).isEqualTo("too many spaces");
    }

    @Test
    void toleratesNullAndBlank() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("   ")).isEmpty();
    }

    @Test
    void leavesAStableMessageUntouched() {
        assertThat(normalizer.normalize("cart is empty")).isEqualTo("cart is empty");
    }
}
