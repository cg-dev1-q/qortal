package org.qortal.at;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight timing instrumentation for the AT fee/state persistence path,
 * i.e. {@code Block.processAtFeesAndStates()} and
 * {@code HSQLDBATRepository.save(ATStateData)}.
 * <p>
 * Timings are accumulated per-AT into thread-safe counters and flushed to the log
 * (at INFO) once every {@code blockInterval} processed blocks, then reset for the next
 * window. Overhead per AT is a handful of {@link System#nanoTime()} calls plus
 * {@link AtomicLong#addAndGet(long)}, so it is safe to leave enabled during a full sync.
 * <p>
 * The window size defaults to {@value #DEFAULT_BLOCK_INTERVAL} blocks and can be
 * overridden with {@code -Dqortal.atMetrics.blockInterval=N}. A JVM shutdown hook
 * flushes any partial window, so short runs still report.
 * <p>
 * Each line is emitted as stable {@code key=value} pairs prefixed with
 * {@value #LOG_PREFIX} for easy machine parsing. Note that {@code atUpdateUs} is
 * inclusive of {@code saveATStatesUs} and {@code saveATStatesDataUs} (plus
 * {@code save(ATData)} and state parsing), since it times the enclosing call.
 */
public class ATStatesLatencyMetrics {

	private static final Logger LOGGER = LogManager.getLogger(ATStatesLatencyMetrics.class);

	/** Marker prefix for log-scraping tools. */
	public static final String LOG_PREFIX = "AT-METRICS";

	private static final long DEFAULT_BLOCK_INTERVAL = 100;

	public static final ATStatesLatencyMetrics INSTANCE = new ATStatesLatencyMetrics();

	/** Blocks per reporting window. */
	private final long blockInterval;

	// Per-AT step timings (nanoseconds) accumulated across the current window
	private final AtomicLong fromAtAddressNanos = new AtomicLong();
	private final AtomicLong modifyBalanceNanos = new AtomicLong();
	private final AtomicLong atUpdateNanos = new AtomicLong();

	// save(ATStateData) internal split (nanoseconds)
	private final AtomicLong saveAtStatesNanos = new AtomicLong();
	private final AtomicLong saveAtStatesDataNanos = new AtomicLong();

	private final AtomicLong atCount = new AtomicLong();
	private final AtomicLong windowBlocks = new AtomicLong();

	// Height range covered by the current window
	private final AtomicLong windowFirstHeight = new AtomicLong(-1);
	private final AtomicLong windowLastHeight = new AtomicLong(-1);

	private ATStatesLatencyMetrics() {
		this.blockInterval = readBlockInterval();

		// Ensure a partial window still gets reported when the node shuts down cleanly
		Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "AT-metrics-flush"));
	}

	private static long readBlockInterval() {
		String property = System.getProperty("qortal.atMetrics.blockInterval");
		if (property == null)
			return DEFAULT_BLOCK_INTERVAL;

		try {
			long value = Long.parseLong(property.trim());
			return value > 0 ? value : DEFAULT_BLOCK_INTERVAL;
		} catch (NumberFormatException e) {
			return DEFAULT_BLOCK_INTERVAL;
		}
	}

	public void addFromAtAddress(long nanos) { this.fromAtAddressNanos.addAndGet(nanos); }
	public void addModifyBalance(long nanos) { this.modifyBalanceNanos.addAndGet(nanos); }
	public void addAtUpdate(long nanos) { this.atUpdateNanos.addAndGet(nanos); }
	public void addSaveAtStates(long nanos) { this.saveAtStatesNanos.addAndGet(nanos); }
	public void addSaveAtStatesData(long nanos) { this.saveAtStatesDataNanos.addAndGet(nanos); }
	public void addAts(long count) { this.atCount.addAndGet(count); }

	/**
	 * Record that one block finished AT persistence, flushing the window every
	 * {@code blockInterval} blocks.
	 *
	 * @param height height of the block just processed, for reporting the window's range
	 */
	public void blockProcessed(int height) {
		this.windowFirstHeight.compareAndSet(-1, height);
		this.windowLastHeight.set(height);

		if (this.windowBlocks.incrementAndGet() % this.blockInterval == 0)
			flush();
	}

	/**
	 * Log and reset the current window. Safe to call at any time; a window that saw no
	 * ATs logs nothing, to avoid noise on nodes with little/no AT activity.
	 */
	public void flush() {
		// Snapshot-and-reset the window
		long blocks = this.windowBlocks.getAndSet(0);
		long ats = this.atCount.getAndSet(0);
		long fromAt = this.fromAtAddressNanos.getAndSet(0);
		long modBal = this.modifyBalanceNanos.getAndSet(0);
		long update = this.atUpdateNanos.getAndSet(0);
		long saveStates = this.saveAtStatesNanos.getAndSet(0);
		long saveStatesData = this.saveAtStatesDataNanos.getAndSet(0);
		long firstHeight = this.windowFirstHeight.getAndSet(-1);
		long lastHeight = this.windowLastHeight.getAndSet(-1);

		if (ats == 0 || blocks == 0)
			return;

		LOGGER.info(String.format(
				"%s blocks=%d heightFrom=%d heightTo=%d ats=%d atsPerBlock=%.2f "
						+ "fromATAddressUs=%.1f modifyBalanceUs=%.1f atUpdateUs=%.1f "
						+ "saveATStatesUs=%.1f saveATStatesDataUs=%.1f "
						+ "fromATAddressMs=%.1f modifyBalanceMs=%.1f atUpdateMs=%.1f",
				LOG_PREFIX, blocks, firstHeight, lastHeight, ats, (double) ats / blocks,
				fromAt / 1e3 / ats, modBal / 1e3 / ats, update / 1e3 / ats,
				saveStates / 1e3 / ats, saveStatesData / 1e3 / ats,
				fromAt / 1e6, modBal / 1e6, update / 1e6));
	}
}
