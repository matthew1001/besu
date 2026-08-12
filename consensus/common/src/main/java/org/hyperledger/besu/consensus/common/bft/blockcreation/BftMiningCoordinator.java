/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.consensus.common.bft.blockcreation;

import org.hyperledger.besu.consensus.common.bft.BftEventQueue;
import org.hyperledger.besu.consensus.common.bft.BftExecutors;
import org.hyperledger.besu.consensus.common.bft.BftProcessor;
import org.hyperledger.besu.consensus.common.bft.events.NewChainHead;
import org.hyperledger.besu.consensus.common.bft.statemachine.BftEventHandler;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.blockcreation.MiningCoordinator;
import org.hyperledger.besu.ethereum.chain.BlockAddedEvent;
import org.hyperledger.besu.ethereum.chain.BlockAddedObserver;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.plugin.services.BesuEvents;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Bft mining coordinator. */
public class BftMiningCoordinator implements MiningCoordinator, BlockAddedObserver {

  private enum State {
    /** Never enabled or started. */
    UNINITIALIZED,
    /** Idle state. */
    IDLE,
    /** Running state. */
    RUNNING,
    /** Stopped state. */
    STOPPED,
    /** Paused state. */
    PAUSED,
  }

  // Sentinel for "observeBlockAdded() has never been called", since 0 is a valid subscriber ID
  // that some other, unrelated observer could legitimately hold.
  private static final long NOT_REGISTERED = -1;

  /** How long {@link #awaitStop()} waits for an in-flight sync-state transition to finish. */
  private static final Duration TRANSITION_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(BftMiningCoordinator.class);

  private final BftEventHandler eventHandler;
  private final BftProcessor bftProcessor;
  private final BftBlockCreatorFactory<?> blockCreatorFactory;

  /** The Blockchain. */
  protected final Blockchain blockchain;

  private final BftEventQueue eventQueue;
  private final BftExecutors bftExecutors;

  private volatile long blockAddedObserverId = NOT_REGISTERED;
  private final AtomicReference<State> state = new AtomicReference<>(State.UNINITIALIZED);

  // The sync-state driven lifecycle. Sync-status notifications only record the state the
  // coordinator *should* be in and hand off to transitionExecutor; see subscribe().
  private final AtomicBoolean desiredMining = new AtomicBoolean(false);
  private final AtomicReference<String> transitionReason = new AtomicReference<>("");
  private volatile ExecutorService transitionExecutor;
  private volatile boolean shuttingDown = false;

  private SyncState syncState;

  /**
   * Instantiates a new Bft mining coordinator.
   *
   * @param bftExecutors the bft executors
   * @param eventHandler the event handler
   * @param bftProcessor the bft processor
   * @param blockCreatorFactory the block creator factory
   * @param blockchain the blockchain
   * @param eventQueue the event queue
   */
  public BftMiningCoordinator(
      final BftExecutors bftExecutors,
      final BftEventHandler eventHandler,
      final BftProcessor bftProcessor,
      final BftBlockCreatorFactory<?> blockCreatorFactory,
      final Blockchain blockchain,
      final BftEventQueue eventQueue) {
    this.bftExecutors = bftExecutors;
    this.eventHandler = eventHandler;
    this.bftProcessor = bftProcessor;
    this.blockCreatorFactory = blockCreatorFactory;
    this.eventQueue = eventQueue;

    this.blockchain = blockchain;
  }

  /**
   * Instantiates a new Bft mining coordinator.
   *
   * @param bftExecutors the bft executors
   * @param eventHandler the event handler
   * @param bftProcessor the bft processor
   * @param blockCreatorFactory the block creator factory
   * @param blockchain the blockchain
   * @param eventQueue the event queue
   * @param syncState the sync state
   */
  public BftMiningCoordinator(
      final BftExecutors bftExecutors,
      final BftEventHandler eventHandler,
      final BftProcessor bftProcessor,
      final BftBlockCreatorFactory<?> blockCreatorFactory,
      final Blockchain blockchain,
      final BftEventQueue eventQueue,
      final SyncState syncState) {
    this.bftExecutors = bftExecutors;
    this.eventHandler = eventHandler;
    this.bftProcessor = bftProcessor;
    this.blockCreatorFactory = blockCreatorFactory;
    this.eventQueue = eventQueue;

    this.blockchain = blockchain;
    this.syncState = syncState;
  }

  @Override
  public void start() {
    // Record the intent as well as acting on it, so a transition still queued on
    // transitionExecutor from an earlier sync-status change cannot undo a direct caller. Runner
    // calls stop() immediately before awaitStop() during shutdown, which is the case that matters.
    desiredMining.set(true);
    if (state.compareAndSet(State.IDLE, State.RUNNING)
        || state.compareAndSet(State.STOPPED, State.RUNNING)) {
      bftProcessor.start();
      bftExecutors.start();
      blockAddedObserverId = blockchain.observeBlockAdded(this);
      eventHandler.start();
      bftExecutors.executeBftProcessor(bftProcessor);
    }
  }

  @Override
  public void stop() {
    // See start(): record the intent so a queued transition cannot restart us behind the caller's
    // back.
    desiredMining.set(false);
    // Stop from RUNNING, PAUSED, or IDLE: the merge transition watcher calls disable()
    // (RUNNING -> PAUSED) immediately before stop(), and disable()/enable() never actually
    // touch the processor/executors themselves (they only flip this state), so a coordinator
    // that was started and then disabled/re-enabled without an intervening stop() can still be
    // sitting in IDLE with its processor genuinely running. PAUSED and IDLE are also reachable
    // without ever having started (e.g. enable() alone, before start()); the teardown below is
    // still safe in that case: bftProcessor.awaitStop() returns immediately if its event loop
    // never ran, blockchain.removeObserver() is skipped when no observer was ever registered,
    // and eventHandler.stop()/bftExecutors.stop() are self-guarded no-ops when never started.
    if (state.compareAndSet(State.RUNNING, State.STOPPED)
        || state.compareAndSet(State.PAUSED, State.STOPPED)
        || state.compareAndSet(State.IDLE, State.STOPPED)) {
      if (blockAddedObserverId != NOT_REGISTERED) {
        blockchain.removeObserver(blockAddedObserverId);
      }
      bftProcessor.stop();
      // The merge transition watcher invokes stop() from the BFT event thread itself
      // (via the block-added observers fired while QBFT imports the terminal block).
      // The shutdown flag is already set, so no further events will be dispatched;
      // the blocking teardown must not run on the event thread or awaitStop() would
      // wait on the thread's own exit.
      if (bftProcessor.isEventThread()) {
        final Thread teardown = new Thread(this::completeStop, "BftMiningCoordinator-stop");
        teardown.setDaemon(true);
        teardown.start();
      } else {
        completeStop();
      }
    }
  }

  private void completeStop() {
    // Make sure the processor has stopped before shutting down the executors
    try {
      bftProcessor.awaitStop();
    } catch (final InterruptedException e) {
      LOG.debug("Interrupted while waiting for BftProcessor to stop.", e);
      Thread.currentThread().interrupt();
    }
    eventHandler.stop();
    bftExecutors.stop();
  }

  @Override
  public void subscribe() {
    if (syncState == null) {
      return;
    }

    // Lifecycle transitions run on a dedicated single thread rather than on the thread that
    // published the sync-status change. Two reasons, both of which have caused validators to
    // wedge until restarted:
    //
    //  1. SyncState invokes its listeners while holding its own monitor, and stop() blocks in
    //     BftProcessor.awaitStop() waiting for the BFT event thread to leave its dispatch loop.
    //     That event thread imports blocks, which fires the block-added observers inline, one of
    //     which is SyncState's own observer calling the synchronized checkInSync(). Stopping
    //     inline therefore closes a deadlock cycle: the publisher waits for the event thread
    //     while holding the monitor the event thread needs. Everything sync-related then stops
    //     for good, because the chain-download loop can no longer set or clear a sync target.
    //
    //  2. start() and stop() are multi-step sequences over bftProcessor/bftExecutors guarded
    //     only by a leading CAS. Interleaving them can leave the coordinator RUNNING with
    //     shut-down executors, in which case isMining() reports true, nothing is mined, and
    //     every subsequent start() is a no-op CAS.
    //
    // Notifications now only record the state we should be in; the executor reconciles towards
    // it. Because reconcile() re-reads desiredMining, the last recorded intent always wins no
    // matter what order the notifications arrive in or how they interleave.
    shuttingDown = false;
    transitionExecutor =
        Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("BftMiningCoordinator-transitions")
                .setDaemon(true)
                .build());

    syncState.subscribeSyncStatus(_ -> requestMiningState(!isSyncing(), "sync status change"));

    syncState.subscribeCompletionReached(
        new BesuEvents.InitialSyncCompletionListener() {
          @Override
          public void onInitialSyncCompleted() {
            requestMiningState(!isSyncing(), "initial sync completed");
          }

          @Override
          public void onInitialSyncRestart() {
            // Nothing to do. The mining coordinator won't be started until
            // sync has completed.
          }
        });
  }

  private boolean isSyncing() {
    return syncState.syncTarget().isPresent() || !syncState.isInitialSyncPhaseDone();
  }

  /**
   * Records the state the coordinator should be in and queues a reconciliation. Never blocks, so it
   * is safe to call from a thread holding a lock (as SyncState's publishers do).
   *
   * @param mine whether the coordinator should be mining
   * @param reason operator-facing description of why, for the transition log line
   */
  private void requestMiningState(final boolean mine, final String reason) {
    final ExecutorService executor = transitionExecutor;
    if (executor == null || shuttingDown) {
      return;
    }
    desiredMining.set(mine);
    transitionReason.set(reason);
    try {
      executor.execute(this::reconcileMiningState);
    } catch (final RejectedExecutionException e) {
      // Only reachable once awaitStop() has shut the executor down, i.e. we are terminating.
      LOG.debug("Ignoring BFT mining coordinator transition request during shutdown", e);
    }
  }

  /**
   * Drives the coordinator towards the most recently requested state. Runs only on
   * transitionExecutor, so start() and stop() can never overlap, and re-reading desiredMining here
   * rather than capturing it per request is what makes the last request win.
   */
  private void reconcileMiningState() {
    if (shuttingDown) {
      return;
    }
    final boolean mine = desiredMining.get();
    if (mine == isMining()) {
      // Already where we want to be; a superseded or duplicate request.
      return;
    }
    final String reason = transitionReason.get();
    try {
      if (mine) {
        LOG.info("Starting BFT mining coordinator following sync (trigger: {})", reason);
        enable();
        start();
      } else {
        LOG.info("Stopping BFT mining coordinator while we are syncing (trigger: {})", reason);
        stop();
      }
    } catch (final RuntimeException e) {
      // Must not escape: this used to run inside SyncState.publishSyncStatus, where a throw
      // propagated out of Subscribers.forEach (created without suppressCallbackExceptions),
      // skipped checkInSync() and killed PipelineChainDownloader's download loop for good --
      // repeatUnlessDownloadComplete() is not covered by its own exceptionallyCompose().
      LOG.error("Failed to {} BFT mining coordinator ({})", mine ? "start" : "stop", reason, e);
    }
  }

  @Override
  public void awaitStop() throws InterruptedException {
    shuttingDown = true;
    final ExecutorService executor = transitionExecutor;
    if (executor != null) {
      executor.shutdown();
      if (!executor.awaitTermination(TRANSITION_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        LOG.error("BFT mining coordinator transition executor did not shutdown cleanly.");
        executor.shutdownNow();
      }
    }
    bftExecutors.awaitStop();
  }

  @Override
  public boolean enable() {
    // Return true if we're already running or idle, or successfully switch to idle. UNINITIALIZED
    // (the initial state) is treated the same as PAUSED here: neither has ever been started.
    return state.get() == State.RUNNING
        || state.get() == State.IDLE
        || state.compareAndSet(State.PAUSED, State.IDLE)
        || state.compareAndSet(State.UNINITIALIZED, State.IDLE);
  }

  @Override
  public boolean disable() {
    // UNINITIALIZED (the initial state) is already at rest, same as PAUSED: report success
    // without transitioning, there being nothing to disable.
    return state.get() == State.PAUSED
        || state.get() == State.UNINITIALIZED
        || state.compareAndSet(State.IDLE, State.PAUSED)
        || state.compareAndSet(State.RUNNING, State.PAUSED);
  }

  @Override
  public boolean isMining() {
    return state.get() == State.RUNNING;
  }

  @Override
  public Wei getMinTransactionGasPrice() {
    return blockCreatorFactory.getMinTransactionGasPrice();
  }

  @Override
  public Wei getMinPriorityFeePerGas() {
    return blockCreatorFactory.getMinPriorityFeePerGas();
  }

  @Override
  public Optional<Block> createBlock(
      final BlockHeader parentHeader,
      final List<Transaction> transactions,
      final List<BlockHeader> ommers) {
    // One-off block creation has not been implemented
    return Optional.empty();
  }

  @Override
  public Optional<Block> createBlock(final BlockHeader parentHeader, final long timestamp) {
    // One-off block creation has not been implemented
    return Optional.empty();
  }

  @Override
  public void changeTargetGasLimit(final Long targetGasLimit) {
    blockCreatorFactory.changeTargetGasLimit(targetGasLimit);
  }

  @Override
  public void onBlockAdded(final BlockAddedEvent event) {
    if (event.isNewCanonicalHead()) {
      LOG.trace("New canonical head detected");
      eventQueue.add(new NewChainHead(event.getHeader()));
    }
  }

  @Override
  public void removeObserver() {
    blockchain.removeObserver(blockAddedObserverId);
  }
}
