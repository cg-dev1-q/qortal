package org.qortal.test.naming;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.block.BlockChain;
import org.qortal.controller.repository.NamesDatabaseIntegrityCheck;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryFactory;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.settings.Settings;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Unicode;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

public class IntegrityTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testValidName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Run the database integrity check for this name
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));

            // Ensure the name still exists and the data is still correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    // Test integrity check after renaming to something else and then back again
    // This was originally confusing the rebuildName() code and creating a loop
    @Test
    public void testUpdateNameLoop() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String initialName = "initial-name";
            String initialData = "{\"age\":30}";
            String initialReducedName = "initia1-name";

            TransactionData initialTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), initialName, initialData);
            initialTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(initialTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, initialTransactionData, alice);

            // Check initial name exists
            assertTrue(repository.getNameRepository().nameExists(initialName));
            assertNotNull(repository.getNameRepository().fromReducedName(initialReducedName));

            // Update the name to something new
            String newName = "new-name";
            String newData = "";
            String newReducedName = "new-name";
            TransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), initialName, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Check old name no longer exists
            assertFalse(repository.getNameRepository().nameExists(initialName));
            assertNull(repository.getNameRepository().fromReducedName(initialReducedName));

            // Check new name exists
            assertTrue(repository.getNameRepository().nameExists(newName));
            assertNotNull(repository.getNameRepository().fromReducedName(newReducedName));

            // Check updated timestamp is correct
            assertEquals((Long) updateTransactionData.getTimestamp(), repository.getNameRepository().fromName(newName).getUpdated());

            // Update the name to another new name
            String newName2 = "new-name-2";
            String newData2 = "";
            String newReducedName2 = "new-name-2";
            TransactionData updateTransactionData2 = new UpdateNameTransactionData(TestTransaction.generateBase(alice), newName, newName2, newData2);
            TransactionUtils.signAndMint(repository, updateTransactionData2, alice);

            // Check old name no longer exists
            assertFalse(repository.getNameRepository().nameExists(newName));
            assertNull(repository.getNameRepository().fromReducedName(newReducedName));

            // Check new name exists
            assertTrue(repository.getNameRepository().nameExists(newName2));
            assertNotNull(repository.getNameRepository().fromReducedName(newReducedName2));

            // Check updated timestamp is correct
            assertEquals((Long) updateTransactionData2.getTimestamp(), repository.getNameRepository().fromName(newName2).getUpdated());

            // Update the name back to the initial name
            TransactionData updateTransactionData3 = new UpdateNameTransactionData(TestTransaction.generateBase(alice), newName2, initialName, initialData);
            TransactionUtils.signAndMint(repository, updateTransactionData3, alice);

            // Check previous name no longer exists
            assertFalse(repository.getNameRepository().nameExists(newName2));
            assertNull(repository.getNameRepository().fromReducedName(newReducedName2));

            // Check initial name exists again
            assertTrue(repository.getNameRepository().nameExists(initialName));
            assertNotNull(repository.getNameRepository().fromReducedName(initialReducedName));

            // Check updated timestamp is correct
            assertEquals((Long) updateTransactionData3.getTimestamp(), repository.getNameRepository().fromName(initialName).getUpdated());

            // Run the database integrity check for the initial name, to ensure it doesn't get into a loop
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(4, integrityCheck.rebuildName(initialName, repository)); // 4 transactions total

            // Ensure the new name still exists and the data is still correct
            assertTrue(repository.getNameRepository().nameExists(initialName));
            assertEquals(initialData, repository.getNameRepository().fromName(initialName).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateWithBlankNewName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name to Alice
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "initial_name";
            String data = "initial_data";
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Update the name, but keep the new name blank
            String newName = "";
            String newData = "updated_data";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Ensure the original name exists and the data is correct
            assertEquals(name, repository.getNameRepository().fromName(name).getName());
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            // Run the database integrity check for this name
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(name, repository));

            // Ensure the name still exists and the data is still correct
            assertEquals(name, repository.getNameRepository().fromName(name).getName());
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateWithBlankNewNameAndBlankEmojiName() throws DataException {
        // Attempt to simulate a real world problem where an emoji with blank reducedName
        // confused the integrity check by associating it with previous UPDATE_NAME transactions
        // due to them also having a blank "newReducedName"

        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name to Alice
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "initial_name";
            String data = "initial_data";
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Update the name, but keep the new name blank
            String newName = "";
            String newData = "updated_data";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Register emoji name
            String emojiName = "\uD83E\uDD73"; // Translates to a reducedName of ""

            // Ensure that the initial_name isn't associated with the emoji name
            NamesDatabaseIntegrityCheck namesDatabaseIntegrityCheck = new NamesDatabaseIntegrityCheck();
            List<TransactionData> transactions = namesDatabaseIntegrityCheck.fetchAllTransactionsInvolvingName(emojiName, repository);
            assertEquals(0, transactions.size());
        }
    }

    @Test
    public void testMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Run the database integrity check for this name and check that a row was modified
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));

            // Ensure the name exists again and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testMissingNameAfterUpdate() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Update the name
            String newData = "{\"age\":31}";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, name, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Ensure the name still exists and the data has been updated
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Run the database integrity check for this name
            // We expect 2 modifications to be made - the original register name followed by the update
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(name, repository));

            // Ensure the name exists and the data is correct
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testMissingNameAfterRename() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Rename the name
            String newName = "new-name";
            String newData = "{\"age\":31}";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Ensure the new name exists and the data has been updated
            assertEquals(newData, repository.getNameRepository().fromName(newName).getData());

            // Ensure the old name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Now delete the new name, to simulate a database inconsistency
            repository.getNameRepository().delete(newName);

            // Ensure the new name doesn't exist
            assertNull(repository.getNameRepository().fromName(newName));

            // Attempt to register the new name
            transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), newName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(alice);

            // Transaction should be invalid, because the database inconsistency was fixed by RegisterNameTransaction.preProcess()
            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertTrue("Transaction should be invalid", Transaction.ValidationResult.OK != result);
            assertTrue("Name should already be registered", Transaction.ValidationResult.NAME_ALREADY_REGISTERED == result);

            repository.discardChanges();
        }
    }

    @Test
    public void testRegisterMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Attempt to register the name again
            String duplicateName = "TEST-nÁme";
            transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), duplicateName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(alice);

            // Transaction should be invalid, because the database inconsistency was fixed by RegisterNameTransaction.preProcess()
            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertTrue("Transaction should be invalid", Transaction.ValidationResult.OK != result);
            assertTrue("Name should already be registered", Transaction.ValidationResult.NAME_ALREADY_REGISTERED == result);

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String initialName = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), initialName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(initialName).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(initialName);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(initialName));

            // Attempt to update the name
            String newName = "new-name";
            String newData = "";
            TransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), initialName, newName, newData);
            Transaction transaction = Transaction.fromData(repository, updateTransactionData);
            transaction.sign(alice);

            // Transaction should be valid, because the database inconsistency was fixed by UpdateNameTransaction.preProcess()
            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertTrue("Transaction should be valid", Transaction.ValidationResult.OK == result);

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateToMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String initialName = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), initialName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(initialName).getData());

            // Register the second name that we will ultimately try and rename the first name to
            String secondName = "new-missing-name";
            String secondNameData = "{\"data2\":true}";
            transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), secondName, secondNameData);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the second name exists and the data is correct
            assertEquals(secondNameData, repository.getNameRepository().fromName(secondName).getData());

            // Now delete the second name, to simulate a database inconsistency
            repository.getNameRepository().delete(secondName);

            // Ensure the second name doesn't exist
            assertNull(repository.getNameRepository().fromName(secondName));

            // Attempt to rename the first name to the second name
            TransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), initialName, secondName, secondNameData);
            Transaction transaction = Transaction.fromData(repository, updateTransactionData);
            transaction.sign(alice);

            // Transaction should be invalid, because the database inconsistency was fixed by UpdateNameTransaction.preProcess()
            // Therefore the name that we are trying to rename TO already exists
            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertTrue("Transaction should be invalid", Transaction.ValidationResult.OK != result);

            // this assertion has been updated, because the primary name logic now comes into play and you cannot update a primary name when there
            // is other names registered and if your try a NOT SUPPORTED result will be given
            assertTrue("Destination name should already exist", Transaction.ValidationResult.NOT_SUPPORTED == result);

            assertEquals(alice.getPrimaryName(), alice.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));

            repository.discardChanges();
        }
    }

    @Test
    public void testSellMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Attempt to sell the name
            TransactionData sellTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, 123456);
            Transaction transaction = Transaction.fromData(repository, sellTransactionData);
            transaction.sign(alice);

            // Transaction should be valid, because the database inconsistency was fixed by SellNameTransaction.preProcess()
            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertTrue("Transaction should be valid", Transaction.ValidationResult.OK == result);

            repository.discardChanges();
        }
    }

    @Test
    public void testBuyMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Attempt to sell the name
            long amount = 123456;
            TransactionData sellTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, amount);
            TransactionUtils.signAndMint(repository, sellTransactionData, alice);

            // Ensure the name now exists
            assertNotNull(repository.getNameRepository().fromName(name));

            // Now delete the name again, to simulate another database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Bob now attempts to buy the name
            String seller = alice.getAddress();
            PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
            TransactionData buyTransactionData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, amount, seller);
            Transaction transaction = Transaction.fromData(repository, buyTransactionData);
            transaction.sign(bob);

            // Transaction should be valid, because the database inconsistency was fixed by SellNameTransaction.preProcess()
            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertTrue("Transaction should be valid", Transaction.ValidationResult.OK == result);

            repository.discardChanges();
        }
    }

    // Regression test for issue #314:
    // Registering a case-variant of a name that was renamed away should succeed,
    // not return NAME_ALREADY_REGISTERED due to incomplete history replay in rebuildName().
    @Test
    public void testRegisterAfterCaseVariantRename() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

            // Mint past multipleNamesPerAccountHeight so oneNamePerAccount check doesn't interfere
            BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

            // Register "Qombo" as alice
            String originalName = "Qombo";
            RegisterNameTransactionData registerData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), originalName, "{}");
            registerData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerData, alice);
            assertTrue(repository.getNameRepository().nameExists(originalName));

            // Rename "Qombo" -> "something-else" (frees the reduced name "qombo")
            String renamedName = "something-else";
            UpdateNameTransactionData renameData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), originalName, renamedName, "");
            TransactionUtils.signAndMint(repository, renameData, alice);
            assertFalse(repository.getNameRepository().nameExists(originalName));
            assertTrue(repository.getNameRepository().nameExists(renamedName));

            // Simulate DB inconsistency: delete "something-else" from Names
            repository.getNameRepository().delete(renamedName);
            assertNull(repository.getNameRepository().fromName(renamedName));

            // Verify rebuildName finds both REGISTER_NAME and UPDATE_NAME via the case-variant lookup
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            String lowercaseName = "qombo";
            List<org.qortal.data.transaction.TransactionData> txns = integrityCheck.fetchAllTransactionsInvolvingName(lowercaseName, repository);
            assertEquals("rebuildName should find both REGISTER_NAME and UPDATE_NAME for case-variant", 2, txns.size());

            // After rebuild, "qombo" reduced name should not exist (renamed away)
            integrityCheck.rebuildName(lowercaseName, repository);
            assertFalse("qombo reduced name should not exist after rebuildName", repository.getNameRepository().reducedNameExists(lowercaseName));
            repository.discardChanges();

            // Bob tries to register "qombo" — should succeed because "Qombo" was renamed away
            // Before the fix, rebuildName("qombo") found the REGISTER_NAME but missed the UPDATE_NAME
            // due to case-sensitive mismatch, leaving "qombo" in Names → NAME_ALREADY_REGISTERED.
            RegisterNameTransactionData newRegisterData = new RegisterNameTransactionData(TestTransaction.generateBase(bob), lowercaseName, "{}");
            newRegisterData.setFee(new RegisterNameTransaction(null, null).getUnitFee(newRegisterData.getTimestamp()));
            Transaction transaction = Transaction.fromData(repository, newRegisterData);
            transaction.sign(bob);

            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            assertEquals("qombo should be registerable after Qombo was renamed away", Transaction.ValidationResult.OK, result);
        }
    }

    @Ignore("Checks 'live' repository")
    @Test
    public void testRepository() throws DataException {
        Common.setShouldRetainRepositoryAfterTest(true);
        Settings.fileInstance("settings.json"); // use 'live' settings

        String repositoryUrlTemplate = "jdbc:hsqldb:file:%s" + File.separator + "blockchain;create=false;hsqldb.full_log_replay=true";
        String connectionUrl = String.format(repositoryUrlTemplate, Settings.getInstance().getRepositoryPath());
        RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(connectionUrl);
        RepositoryManager.setRepositoryFactory(repositoryFactory);

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<NameData> names = repository.getNameRepository().getAllNames();

            for (NameData nameData : names) {
                String reReduced = Unicode.sanitize(nameData.getName());

                if (reReduced.isBlank()) {
                    System.err.println(String.format("Name '%s' reduced to blank",
                            nameData.getName()
                    ));
                }

                if (!nameData.getReducedName().equals(reReduced)) {
                    System.out.println(String.format("Name '%s' reduced form was '%s' but is now '%s'",
                            nameData.getName(),
                            nameData.getReducedName(),
                            reReduced
                    ));

                    // ...but does another name already have this reduced form?
                    names.stream()
                            .filter(tmpNameData -> tmpNameData.getReducedName().equals(reReduced))
                            .forEach(tmpNameData ->
                                    System.err.println(String.format("Name '%s' new reduced form also matches name '%s'",
                                            nameData.getName(),
                                            tmpNameData.getName()
                                    ))
                            );
                }
            }
        }
    }
}
