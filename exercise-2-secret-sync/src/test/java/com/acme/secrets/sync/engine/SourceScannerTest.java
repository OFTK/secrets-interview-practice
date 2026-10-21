package com.acme.secrets.sync.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.source.FakeVault;
import com.acme.secrets.sync.source.SecretRef;
import com.acme.secrets.sync.source.VaultUnavailableException;
import com.acme.secrets.sync.testing.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SourceScannerTest {

    private static final Instant START = Instant.parse("2026-01-05T09:00:00Z");
    private static final int PAGE_SIZE = 100;
    private static final int TWO_AND_A_HALF_PAGES = 250;
    private static final int PARTIAL_PAGE = 42;
    private static final int TWO_FULL_PAGES = 200;
    private static final String SECRET_VALUE = "s3cr3t";
    private static final String PAYMENTS_DB_PASSWORD = "prod/payments/db-password";
    private static final String ORDERS_DB_PASSWORD = "prod/orders/db-password";
    private static final String DEV_PAYMENTS_DB_PASSWORD = "dev/payments/db-password";
    private static final SyncPolicy PAYMENTS_POLICY =
            new SyncPolicy("payments-app", "prod/payments/*", "aws-payments-app", true);

    private final FakeVault vault = new FakeVault(new MutableClock(START));
    private final SourceScanner scanner = new SourceScanner(vault, PAGE_SIZE);

    @Test
    void scan_totalNotMultipleOfPageSize_returnsAllSecrets() {
        storePaymentsSecrets(TWO_AND_A_HALF_PAGES);

        List<SecretRef> scanned = scanner.scan(PAYMENTS_POLICY);

        assertThat(scanned.size()).as("secrets returned by scan").isEqualTo(TWO_AND_A_HALF_PAGES);
    }

    @Test
    void scan_singlePartialPage_returnsAllSecrets() {
        storePaymentsSecrets(PARTIAL_PAGE);

        List<SecretRef> scanned = scanner.scan(PAYMENTS_POLICY);

        assertThat(scanned.size()).as("secrets returned by scan").isEqualTo(PARTIAL_PAGE);
    }

    @Test
    void scan_exactMultipleOfPageSize_returnsAllSecrets() {
        storePaymentsSecrets(TWO_FULL_PAGES);

        List<SecretRef> scanned = scanner.scan(PAYMENTS_POLICY);

        assertThat(scanned.size()).as("secrets returned by scan").isEqualTo(TWO_FULL_PAGES);
    }

    @Test
    void scan_policyPattern_returnsOnlyMatchingSecrets() {
        storeSecrets(List.of(PAYMENTS_DB_PASSWORD, ORDERS_DB_PASSWORD, DEV_PAYMENTS_DB_PASSWORD));

        List<SecretRef> scanned = scanner.scan(PAYMENTS_POLICY);

        assertThat(scanned).extracting(SecretRef::id).containsExactly(PAYMENTS_DB_PASSWORD);
    }

    @Test
    void scan_vaultUnavailable_throws() {
        storePaymentsSecrets(PARTIAL_PAGE);
        vault.setAvailable(false);

        Throwable thrown = catchThrowable(() -> scanner.scan(PAYMENTS_POLICY));

        assertThat(thrown).isInstanceOf(VaultUnavailableException.class);
    }

    private void storePaymentsSecrets(int count) {
        storeSecrets(IntStream.rangeClosed(1, count)
                .mapToObj(n -> String.format(Locale.ROOT, "prod/payments/svc-%03d", n))
                .toList());
    }

    private void storeSecrets(List<String> ids) {
        ids.forEach(id -> vault.put(id, SECRET_VALUE));
    }
}
