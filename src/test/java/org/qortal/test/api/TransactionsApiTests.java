package org.qortal.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.test.common.ApiCommon;
import org.qortal.transaction.Transaction.TransactionType;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TransactionsApiTests extends ApiCommon {

	private TransactionsResource transactionsResource;

	@Before
	public void buildResource() {
		this.transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
	}

	@Test
	public void test() {
		assertNotNull(this.transactionsResource);
	}

	@Test
	public void testGetPendingTransactions() {
		for (Integer txGroupId : Arrays.asList(null, 0, 1)) {
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, null, null, null));
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, 1, 1, true));
		}
	}

	@Test
	public void testGetUnconfirmedTransactions() {
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, null, null, null));
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, 1, 1, true));
	}

	@Test
	public void testTransferPrivsSerializesRecipient() throws Exception {
		BaseTransactionData base = new BaseTransactionData(System.currentTimeMillis(), 0, new byte[64], new byte[32], 10000L, null);
		TransactionData txData = new TransferPrivsTransactionData(base, aliceAddress);

		JAXBContext jc = JAXBContextFactory.createContext(new Class[] { TransactionData.class }, null);
		Marshaller marshaller = jc.createMarshaller();
		marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

		StringWriter sw = new StringWriter();
		marshaller.marshal(txData, sw);
		String json = sw.toString();

		assertTrue("TRANSFER_PRIVS JSON missing 'recipient' field", json.contains("\"recipient\""));
		assertTrue("TRANSFER_PRIVS JSON missing alice's address", json.contains(aliceAddress));
	}

	@Test
	public void testSearchTransactions() {
		List<TransactionType> txTypes = Arrays.asList(TransactionType.PAYMENT, TransactionType.ISSUE_ASSET);

		for (Integer startBlock : Arrays.asList(null, 1))
			for (Integer blockLimit : Arrays.asList(null, 1))
				for (Integer txGroupId : Arrays.asList(null, 1))
					for (String address : Arrays.asList(null, aliceAddress))
						for (ConfirmationStatus confirmationStatus : ConfirmationStatus.values()) {
							if (confirmationStatus != ConfirmationStatus.CONFIRMED) {
								startBlock = null;
								blockLimit = null;
							}

							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, null, null, null));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, 1, 1, true));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, null, address, confirmationStatus, 1, 1, true));
						}
	}

}
