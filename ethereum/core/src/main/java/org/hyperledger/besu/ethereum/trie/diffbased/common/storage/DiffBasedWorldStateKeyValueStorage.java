/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.trie.diffbased.common.storage;

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_FREEZER_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.STORAGE_FREEZER_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE;
import static org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.flat.ArchiveFlatDbStrategy.DELETED_ACCOUNT_VALUE;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.bonsai.BonsaiContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.storage.StorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.flat.ArchiveFlatDbStrategy;
import org.hyperledger.besu.ethereum.trie.diffbased.common.StorageSubscriber;
import org.hyperledger.besu.ethereum.trie.diffbased.common.storage.flat.FlatDbStrategy;
import org.hyperledger.besu.ethereum.worldstate.FlatDbMode;
import org.hyperledger.besu.ethereum.worldstate.WorldStateKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.util.Subscribers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

import kotlin.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DiffBasedWorldStateKeyValueStorage
    implements WorldStateKeyValueStorage, AutoCloseable {
  private static final Logger LOG =
      LoggerFactory.getLogger(DiffBasedWorldStateKeyValueStorage.class);

  // 0x776f726c64526f6f74
  public static final byte[] WORLD_ROOT_HASH_KEY = "worldRoot".getBytes(StandardCharsets.UTF_8);
  // 0x776f726c64426c6f636b48617368
  public static final byte[] WORLD_BLOCK_HASH_KEY =
      "worldBlockHash".getBytes(StandardCharsets.UTF_8);

  private final AtomicBoolean shouldClose = new AtomicBoolean(false);

  protected final AtomicBoolean isClosed = new AtomicBoolean(false);

  protected final Subscribers<StorageSubscriber> subscribers = Subscribers.create();
  protected final SegmentedKeyValueStorage composedWorldStateStorage;
  protected final KeyValueStorage trieLogStorage;

  public DiffBasedWorldStateKeyValueStorage(final StorageProvider provider) {
    this.composedWorldStateStorage =
        provider.getStorageBySegmentIdentifiers(
            List.of(
                ACCOUNT_INFO_STATE, CODE_STORAGE, ACCOUNT_STORAGE_STORAGE, TRIE_BRANCH_STORAGE));
    this.trieLogStorage =
        provider.getStorageBySegmentIdentifier(KeyValueSegmentIdentifier.TRIE_LOG_STORAGE);
  }

  public DiffBasedWorldStateKeyValueStorage(
      final SegmentedKeyValueStorage composedWorldStateStorage,
      final KeyValueStorage trieLogStorage) {
    this.composedWorldStateStorage = composedWorldStateStorage;
    this.trieLogStorage = trieLogStorage;
  }

  public abstract FlatDbMode getFlatDbMode();

  public abstract FlatDbStrategy getFlatDbStrategy();

  @Override
  public abstract DataStorageFormat getDataStorageFormat();

  public SegmentedKeyValueStorage getComposedWorldStateStorage() {
    return composedWorldStateStorage;
  }

  public KeyValueStorage getTrieLogStorage() {
    return trieLogStorage;
  }

  public Optional<byte[]> getTrieLog(final Hash blockHash) {
    return trieLogStorage.get(blockHash.toArrayUnsafe());
  }

  public Stream<byte[]> streamTrieLogKeys(final long limit) {
    return trieLogStorage.streamKeys().limit(limit);
  }

  public Optional<Bytes> getStateTrieNode(final Bytes location) {
    return composedWorldStateStorage
        .get(TRIE_BRANCH_STORAGE, location.toArrayUnsafe())
        .map(Bytes::wrap);
  }

  public Optional<Bytes> getWorldStateRootHash() {
    return composedWorldStateStorage.get(TRIE_BRANCH_STORAGE, WORLD_ROOT_HASH_KEY).map(Bytes::wrap);
  }

  public Optional<Hash> getWorldStateBlockHash() {
    return composedWorldStateStorage
        .get(TRIE_BRANCH_STORAGE, WORLD_BLOCK_HASH_KEY)
        .map(Bytes32::wrap)
        .map(Hash::wrap);
  }

  public NavigableMap<Bytes32, Bytes> streamFlatAccounts(
      final Bytes startKeyHash, final Bytes32 endKeyHash, final long max) {
    return getFlatDbStrategy()
        .streamAccountFlatDatabase(composedWorldStateStorage, startKeyHash, endKeyHash, max);
  }

  public NavigableMap<Bytes32, Bytes> streamFlatAccounts(
      final Bytes startKeyHash, final Predicate<Pair<Bytes32, Bytes>> takeWhile) {
    return getFlatDbStrategy()
        .streamAccountFlatDatabase(composedWorldStateStorage, startKeyHash, takeWhile);
  }

  public NavigableMap<Bytes32, Bytes> streamFlatStorages(
      final Hash accountHash, final Bytes startKeyHash, final Bytes32 endKeyHash, final long max) {
    return getFlatDbStrategy()
        .streamStorageFlatDatabase(
            composedWorldStateStorage, accountHash, startKeyHash, endKeyHash, max);
  }

  public NavigableMap<Bytes32, Bytes> streamFlatStorages(
      final Hash accountHash,
      final Bytes startKeyHash,
      final Predicate<Pair<Bytes32, Bytes>> takeWhile) {
    return getFlatDbStrategy()
        .streamStorageFlatDatabase(composedWorldStateStorage, accountHash, startKeyHash, takeWhile);
  }

  public boolean isWorldStateAvailable(final Bytes32 rootHash, final Hash blockHash) {
    return composedWorldStateStorage
        .get(TRIE_BRANCH_STORAGE, WORLD_ROOT_HASH_KEY)
        .map(Bytes32::wrap)
        .map(hash -> hash.equals(rootHash) || trieLogStorage.containsKey(blockHash.toArrayUnsafe()))
        .orElse(false);
  }

  @Override
  public void clear() {
    subscribers.forEach(StorageSubscriber::onClearStorage);
    getFlatDbStrategy().clearAll(composedWorldStateStorage);
    composedWorldStateStorage.clear(TRIE_BRANCH_STORAGE);
    trieLogStorage.clear();
  }

  public void clearTrieLog() {
    subscribers.forEach(StorageSubscriber::onClearTrieLog);
    trieLogStorage.clear();
  }

  public void clearTrie() {
    subscribers.forEach(StorageSubscriber::onClearTrie);
    composedWorldStateStorage.clear(TRIE_BRANCH_STORAGE);
  }

  public void clearFlatDatabase() {
    subscribers.forEach(StorageSubscriber::onClearFlatDatabaseStorage);
    getFlatDbStrategy().resetOnResync(composedWorldStateStorage);
  }

  @Override
  public abstract Updater updater();

  public boolean pruneTrieLog(final Hash blockHash) {
    try {
      return trieLogStorage.tryDelete(blockHash.toArrayUnsafe());
    } catch (Exception e) {
      LOG.error("Error pruning trie log for block hash {}", blockHash, e);
      return false;
    }
  }

  /**
   * Move old account state from the primary DB segments to "cold" segments that will only be used
   * for historic state queries. This prevents performance degradation over time for writes to the
   * primary DB segments.
   *
   * @param previousBlockHeader the block header for the previous block, used to get the "nearest
   *     before" state
   * @param accountHash the account to freeze old state for
   * @return the number of account states that were moved to frozen storage
   */
  public int freezePreviousAccountState(
      final Optional<BlockHeader> previousBlockHeader, final Hash accountHash) {
    AtomicInteger frozenStateCount = new AtomicInteger();
    if (previousBlockHeader.isPresent()) {
      try {
        // Get the key for the previous block
        final BonsaiContext previousContext = new BonsaiContext();
        previousContext.setBlockHeader(previousBlockHeader.get());
        final Bytes previousKey =
            ArchiveFlatDbStrategy.calculateArchiveKeyWithMaxSuffix(
                previousContext, accountHash.toArrayUnsafe());

        composedWorldStateStorage
            .getNearestBefore(ACCOUNT_INFO_STATE, previousKey)
            .filter(
                // Ignore deleted entries
                found ->
                    !Arrays.areEqual(
                        DELETED_ACCOUNT_VALUE, found.value().orElse(DELETED_ACCOUNT_VALUE)))
            // Skip "nearest" entries that are for a different account
            .filter(found -> accountHash.commonPrefixLength(found.key()) >= accountHash.size())
            .stream()
            .forEach(
                (nearestKey) -> {
                  SegmentedKeyValueStorageTransaction tx =
                      composedWorldStateStorage.startTransaction();
                  tx.remove(ACCOUNT_INFO_STATE, nearestKey.key().toArrayUnsafe());
                  tx.put(
                      ACCOUNT_FREEZER_STATE,
                      nearestKey.key().toArrayUnsafe(),
                      nearestKey.value().get());
                  tx.commit();
                  frozenStateCount.getAndIncrement();
                });

        LOG.atDebug()
            .setMessage("no previous state for account {} found to move to cold storage")
            .addArgument(accountHash)
            .log();
      } catch (Exception e) {
        LOG.error("Error moving account state for account {} to cold storage", accountHash, e);
      }
    }

    return frozenStateCount.get();
  }

  /**
   * Move old storage state from the primary DB segments to "cold" segments that will only be used
   * for historic state queries. This prevents performance degradation over time for writes to the
   * primary DB segments.
   *
   * @param previousBlockHeader the block header for the previous block, used to get the "nearest
   *     before" state
   * @param storageSlotKey the storage slot to freeze old state for
   * @return the number of storage states that were moved to frozen storage
   */
  public int freezePreviousStorageState(
      final Optional<BlockHeader> previousBlockHeader, final Bytes storageSlotKey) {
    AtomicInteger frozenStateCount = new AtomicInteger();
    if (previousBlockHeader.isPresent()) {
      try {
        // Get the key for the previous block
        final BonsaiContext previousContext = new BonsaiContext();
        previousContext.setBlockHeader(previousBlockHeader.get());
        final Bytes previousKey =
            ArchiveFlatDbStrategy.calculateArchiveKeyWithMaxSuffix(
                previousContext, storageSlotKey.toArrayUnsafe());

        composedWorldStateStorage
            .getNearestBefore(ACCOUNT_STORAGE_STORAGE, previousKey)
            .filter(
                // Ignore deleted entries
                found ->
                    !Arrays.areEqual(
                        DELETED_ACCOUNT_VALUE, found.value().orElse(DELETED_ACCOUNT_VALUE)))
            // Skip "nearest" entries that are for a different account
            .filter(
                found -> storageSlotKey.commonPrefixLength(found.key()) >= storageSlotKey.size())
            .stream()
            .forEach(
                (nearestKey) -> {
                  SegmentedKeyValueStorageTransaction tx =
                      composedWorldStateStorage.startTransaction();
                  tx.remove(ACCOUNT_STORAGE_STORAGE, nearestKey.key().toArrayUnsafe());
                  tx.put(
                      STORAGE_FREEZER_STATE,
                      nearestKey.key().toArrayUnsafe(),
                      nearestKey.value().get());
                  tx.commit();
                  frozenStateCount.getAndIncrement();
                });

        LOG.atDebug()
            .setMessage("no previous state for storage {} found to move to cold storage")
            .addArgument(storageSlotKey)
            .log();
      } catch (Exception e) {
        LOG.error("Error moving storage state for slot {} to cold storage", storageSlotKey, e);
      }
    }

    return frozenStateCount.get();
  }

  @Override
  public synchronized void close() throws Exception {
    // when the storage clears, close
    shouldClose.set(true);
    tryClose();
  }

  public synchronized long subscribe(final StorageSubscriber sub) {
    if (isClosed.get()) {
      throw new RuntimeException("Storage is marked to close or has already closed");
    }
    return subscribers.subscribe(sub);
  }

  public synchronized void unSubscribe(final long id) {
    subscribers.unsubscribe(id);
    try {
      tryClose();
    } catch (Exception e) {
      LOG.atWarn()
          .setMessage("exception while trying to close : {}")
          .addArgument(e::getMessage)
          .log();
    }
  }

  protected synchronized void tryClose() throws Exception {
    if (shouldClose.get() && subscribers.getSubscriberCount() < 1) {
      doClose();
    }
  }

  protected synchronized void doClose() throws Exception {
    if (!isClosed.get()) {
      // alert any subscribers we are closing:
      subscribers.forEach(StorageSubscriber::onCloseStorage);

      // close all of the KeyValueStorages:
      composedWorldStateStorage.close();
      trieLogStorage.close();

      // set storage closed
      isClosed.set(true);
    }
  }

  public interface Updater extends WorldStateKeyValueStorage.Updater {

    DiffBasedWorldStateKeyValueStorage.Updater saveWorldState(
        final Bytes blockHash, final Bytes32 nodeHash, final Bytes node);

    SegmentedKeyValueStorageTransaction getWorldStateTransaction();

    KeyValueStorageTransaction getTrieLogStorageTransaction();

    @Override
    void commit();

    void rollback();
  }
}
