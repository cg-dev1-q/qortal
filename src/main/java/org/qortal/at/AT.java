package org.qortal.at;

import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.repository.ATRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.AtTransaction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AT {

	// Properties
	private Repository repository;
	private ATData atData;
	private ATStateData atStateData;

	// Constructors

	public AT(Repository repository, ATData atData, ATStateData atStateData) {
		this.repository = repository;
		this.atData = atData;
		this.atStateData = atStateData;
	}

	public AT(Repository repository, ATData atData) {
		this(repository, atData, null);
	}

	/** Constructs AT-handling object when deploying AT */
	public AT(Repository repository, DeployAtTransactionData deployATTransactionData) throws DataException {
		this.repository = repository;

		String atAddress = deployATTransactionData.getAtAddress();
		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		byte[] creatorPublicKey = deployATTransactionData.getCreatorPublicKey();
		long creation = deployATTransactionData.getTimestamp();
		long assetId = deployATTransactionData.getAssetId();

		// Just enough AT data to allow API to query initial balances, etc.
		ATData skeletonAtData = new ATData(atAddress, creatorPublicKey, creation, assetId);

		long blockTimestamp = Timestamp.toLong(height, 0);
		QortalATAPI api = new QortalATAPI(repository, skeletonAtData, blockTimestamp);
		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

		MachineState machineState = new MachineState(api, loggerFactory, deployATTransactionData.getCreationBytes());

		byte[] codeBytes = machineState.getCodeBytes();
		byte[] codeHash = Crypto.digest(codeBytes);

		this.atData = new ATData(atAddress, creatorPublicKey, creation, machineState.version, assetId, codeBytes, codeHash,
				machineState.isSleeping(), machineState.getSleepUntilHeight(), machineState.isFinished(), machineState.hadFatalError(),
				machineState.isFrozen(), machineState.getFrozenBalance(), null);

		byte[] stateData = machineState.toBytes();
		byte[] stateHash = Crypto.digest(stateData);

		this.atStateData = new ATStateData(atAddress, height, stateData, stateHash, 0L, true, null);
	}

	// Getters / setters

	public ATStateData getATStateData() {
		return this.atStateData;
	}

	// Processing

	public void deploy() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();
		atRepository.save(this.atData);

		atRepository.save(this.atStateData);
	}

	public void undeploy() throws DataException {
		// AT states deleted implicitly by repository
		this.repository.getATRepository().delete(this.atData.getATAddress());
	}

	/**
	 * Potentially execute AT.
	 * <p>
	 * Note that sleep-until-message support might set/reset
	 * sleep-related flags/values.
	 * <p>
	 * {@link #getATStateData()} will return null if nothing happened.
	 * <p>
	 * @param blockHeight
	 * @param blockTimestamp
	 * @return AT-generated transactions, possibly empty
	 * @throws DataException
	 */
	public List<AtTransaction> run(int blockHeight, long blockTimestamp) throws DataException {
		String atAddress = this.atData.getATAddress();

		QortalATAPI api = new QortalATAPI(repository, this.atData, blockTimestamp);
		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

		if (!api.willExecute(blockHeight))
			// this.atStateData will be null
			return Collections.emptyList();

		// Fetch latest ATStateData for this AT
		ATStateData latestAtStateData = this.repository.getATRepository().getLatestATState(atAddress);

		// There should be at least initial deployment AT state data
		if (latestAtStateData == null)
			throw new IllegalStateException("No previous AT state data found");

		// [Re]create AT machine state using AT state data or from scratch as applicable
		byte[] codeBytes = this.atData.getCodeBytes();
		MachineState state = MachineState.fromBytes(api, loggerFactory, latestAtStateData.getStateData(), codeBytes);
		try {
			api.preExecute(state);
			state.execute();
		} catch (Exception e) {
			throw new DataException(String.format("Uncaught exception while running AT '%s'", atAddress), e);
		}

		byte[] stateData = state.toBytes();
		byte[] stateHash = Crypto.digest(stateData);

		// Nothing happened?
		if (state.getSteps() == 0 && Arrays.equals(stateHash, latestAtStateData.getStateHash()))
			// We currently want to execute frozen ATs, to maintain backwards support.
			if (!state.isFrozen())
				// this.atStateData will be null
				return Collections.emptyList();

		long atFees = api.calcFinalFees(state);
		Long sleepUntilMessageTimestamp = this.atData.getSleepUntilMessageTimestamp();

		this.atStateData = new ATStateData(atAddress, blockHeight, stateData, stateHash, atFees, false, sleepUntilMessageTimestamp);

		return api.getTransactions();
	}

	/**
	 * Saves the given AT state and brings the AT's execution flags into line with it.
	 * <p>
	 * Deliberately does not read the AT back first. Every value written here is derived from
	 * <tt>atStateData</tt>, so fetching the AT would only retrieve its immutable fields -
	 * including the <tt>code_bytes</tt> BLOB - in order to write them straight back unchanged.
	 * Block processing calls this for every executable AT of every block, where that read and
	 * rewrite of unchanging bytecode measured ~43% of all AT persistence time.
	 */
	public static void updateFromState(Repository repository, ATStateData atStateData) throws DataException {
		// Extract minimal/flags-only AT machine state using AT state data
		MachineState state = MachineState.flagsOnlyfromBytes(atStateData.getStateData());

		// Save latest AT state data
		repository.getATRepository().save(atStateData);

		// Update AT info in repository too
		repository.getATRepository().updateFlags(
				atStateData.getATAddress(),
				state.isSleeping(),
				state.getSleepUntilHeight(),
				state.isFinished(),
				state.hadFatalError(),
				state.isFrozen(),
				state.getFrozenBalance(),
				// Special sleep-until-message support
				atStateData.getSleepUntilMessageTimestamp());
	}

	public void revert(int blockHeight, long blockTimestamp) throws DataException {
		String atAddress = this.atData.getATAddress();

		// Delete old AT state data from repository
		this.repository.getATRepository().delete(atAddress, blockHeight);

		if (this.atStateData.isInitial())
			return;

		// Load previous state data
		ATStateData previousStateData = this.repository.getATRepository().getLatestATState(atAddress);
		if (previousStateData == null)
			throw new DataException("Can't find previous AT state data for " + atAddress);

		// Extract minimal/flags-only AT machine state using AT state data
		MachineState state = MachineState.flagsOnlyfromBytes(previousStateData.getStateData());

		// Update AT info in repository
		this.atData.setIsSleeping(state.isSleeping());
		this.atData.setSleepUntilHeight(state.getSleepUntilHeight());
		this.atData.setIsFinished(state.isFinished());
		this.atData.setHadFatalError(state.hadFatalError());
		this.atData.setIsFrozen(state.isFrozen());
		this.atData.setFrozenBalance(state.getFrozenBalance());

		// Special sleep-until-message support
		this.atData.setSleepUntilMessageTimestamp(previousStateData.getSleepUntilMessageTimestamp());

		this.repository.getATRepository().save(this.atData);
	}

}
