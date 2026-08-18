package dev.stacklight.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The receipt reader.
 *
 * <p>A parser written by hand is a liability without tests, and this one has a specific
 * hazard: the values it reads through are error messages the collector copied from
 * somewhere else, so they can contain the very punctuation the scan is looking for. Half of
 * what is below is about text that tries to look like structure.
 */
class BatchReceiptTests {

    @Test
    void readsAnEntryPerResult() {
        List<BatchReceipt.Entry> entries =
                BatchReceipt.parse(
                        """
                        {"accepted":3,"results":[
                          {"eventId":"a","stored":true,"error":null,"retryable":false},
                          {"eventId":"b","stored":true,"error":null,"retryable":false},
                          {"eventId":"c","stored":true,"error":null,"retryable":false}]}
                        """);

        assertThat(entries).hasSize(3);
        assertThat(entries).allMatch(entry -> !entry.failed());
    }

    @Test
    void separatesWhatCanBeRetriedFromWhatCannot() {
        List<BatchReceipt.Entry> entries =
                BatchReceipt.parse(
                        """
                        {"results":[
                          {"error":null,"retryable":false},
                          {"error":"database unavailable","retryable":true},
                          {"error":"service must not be blank","retryable":false}]}
                        """);

        assertThat(entries.get(0).failed()).isFalse();
        assertThat(entries.get(1).failed()).isTrue();
        assertThat(entries.get(1).retryable()).isTrue();
        assertThat(entries.get(2).failed()).isTrue();
        assertThat(entries.get(2).retryable()).isFalse();
    }

    @Test
    void anErrorMessageThatLooksLikeStructureIsStillJustAMessage() {
        // The message carries a brace, a bracket and a quoted key. A scan that did not
        // track strings would read the braces as entries and the key as a field.
        List<BatchReceipt.Entry> entries =
                BatchReceipt.parse(
                        """
                        {"results":[
                          {"error":"unexpected {\\"retryable\\":true} in [input]","retryable":false},
                          {"error":null,"retryable":false}]}
                        """);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).failed()).isTrue();
        assertThat(entries.get(0).retryable()).isFalse();
        assertThat(entries.get(1).failed()).isFalse();
    }

    @Test
    void aKeyNameAppearingInsideAMessageIsNotReadAsAKey() {
        List<BatchReceipt.Entry> entries =
                BatchReceipt.parse(
                        "{\"results\":[{\"error\":\"the word results: appeared here\",\"retryable\":true}]}");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).failed()).isTrue();
        assertThat(entries.get(0).retryable()).isTrue();
    }

    @Test
    void fieldOrderDoesNotMatter() {
        List<BatchReceipt.Entry> entries =
                BatchReceipt.parse(
                        "{\"results\":[{\"retryable\":true,\"stored\":false,\"error\":\"boom\"}]}");

        assertThat(entries.get(0).failed()).isTrue();
        assertThat(entries.get(0).retryable()).isTrue();
    }

    @Test
    void anEmptyResultArrayReadsAsNoEntries() {
        assertThat(BatchReceipt.parse("{\"accepted\":0,\"results\":[]}")).isEmpty();
    }

    @Test
    void anythingItCannotMakeSenseOfIsEmptyRatherThanWrong() {
        // The caller reads an empty list as "the collector answered 2xx, assume it has
        // them all", which is the safe direction: nothing is re-sent on a bad receipt.
        assertThat(BatchReceipt.parse(null)).isEmpty();
        assertThat(BatchReceipt.parse("not json")).isEmpty();
        assertThat(BatchReceipt.parse("{\"accepted\":2}")).isEmpty();
    }
}
