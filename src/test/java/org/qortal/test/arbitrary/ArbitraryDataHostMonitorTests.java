package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Verifies that the batched fromSignatures() path in ArbitraryDataHostMonitor
 * returns the same results as a single call, and doesn't silently drop entries
 * when the signature count exceeds the batch size (500).
 */
public class ArbitraryDataHostMonitorTests extends Common {

    private static final int BATCH_SIZE = 500; // must match ArbitraryDataHostMonitor.BATCH_SIZE

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testBatchedFromSignaturesMatchesSingleCall() throws DataException {
        final int count = BATCH_SIZE + 100; // cross the batch boundary

        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

            List<byte[]> signatures = new ArrayList<>(count);

            long baseTimestamp = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                // Use a unique timestamp per transaction so each produces a distinct signature.
                long timestamp = baseTimestamp + i;
                byte[] lastRef = alice.getLastReference();
                PaymentTransactionData txData = new PaymentTransactionData(
                        new org.qortal.data.transaction.BaseTransactionData(
                                timestamp,
                                org.qortal.group.Group.NO_GROUP,
                                lastRef,
                                alice.getPublicKey(),
                                0L,
                                null),
                        alice.getAddress(), 1L);
                Transaction tx = Transaction.fromData(repository, txData);
                tx.sign(alice);
                tx.setInitialApprovalStatus();
                repository.getTransactionRepository().save(txData);
                signatures.add(txData.getSignature());
            }
            repository.saveChanges();

            // single call — reference result
            List<TransactionData> single = repository.getTransactionRepository().fromSignatures(signatures);

            // batched — mirrors ArbitraryDataHostMonitor logic
            List<TransactionData> batched = new ArrayList<>(signatures.size());
            for (int i = 0; i < signatures.size(); i += BATCH_SIZE) {
                List<byte[]> batch = signatures.subList(i, Math.min(i + BATCH_SIZE, signatures.size()));
                batched.addAll(repository.getTransactionRepository().fromSignatures(batch));
            }

            assertEquals("batched fromSignatures must return same count as single call", single.size(), batched.size());
            assertEquals("all inserted transactions must be found", count, batched.size());
        }
    }
}
