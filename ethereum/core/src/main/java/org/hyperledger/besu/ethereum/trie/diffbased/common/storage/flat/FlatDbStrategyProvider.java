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
package org.hyperledger.besu.ethereum.trie.diffbased.common.storage.flat;

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE;
import static org.hyperledger.besu.ethereum.trie.diffbased.common.storage.DiffBasedWorldStateKeyValueStorage.WORLD_ROOT_HASH_KEY;

import org.hyperledger.besu.ethereum.bonsai.BonsaiContext;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.flat.FullFlatDbStrategy;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.flat.PartialFlatDbStrategy;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.FlatDbMode;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlatDbStrategyProvider {
  private static final Logger LOG = LoggerFactory.getLogger(FlatDbStrategyProvider.class);

  // 0x666C61744462537461747573
  public static final byte[] FLAT_DB_MODE = "flatDbStatus".getBytes(StandardCharsets.UTF_8);
  private final MetricsSystem metricsSystem;
  private final DataStorageConfiguration dataStorageConfiguration;
  protected FlatDbMode flatDbMode;
  protected FlatDbStrategy flatDbStrategy;

  public FlatDbStrategyProvider(
      final MetricsSystem metricsSystem, final DataStorageConfiguration dataStorageConfiguration) {
    this.metricsSystem = metricsSystem;
    this.dataStorageConfiguration = dataStorageConfiguration;
  }

  public void loadFlatDbStrategy(final SegmentedKeyValueStorage composedWorldStateStorage) {
    // derive our flatdb strategy from db or default:
    var newFlatDbMode = deriveFlatDbStrategy(composedWorldStateStorage);

    // if  flatDbMode is not loaded or has changed, reload flatDbStrategy
    if (this.flatDbMode == null || !this.flatDbMode.equals(newFlatDbMode)) {
      this.flatDbMode = newFlatDbMode;
      final CodeStorageStrategy codeStorageStrategy =
          deriveUseCodeStorageByHash(composedWorldStateStorage)
              ? new CodeHashCodeStorageStrategy()
              : new AccountHashCodeStorageStrategy();
      if (flatDbMode == FlatDbMode.FULL) {
        this.flatDbStrategy = new FullFlatDbStrategy(metricsSystem, codeStorageStrategy);
      } else if (flatDbMode == FlatDbMode.ARCHIVE) {
        final BonsaiContext context = new BonsaiContext();
        this.flatDbStrategy =
            new ArchiveFlatDbStrategy(
                context, metricsSystem, new ArchiveCodeStorageStrategy(context));
      } else {
        this.flatDbStrategy = new PartialFlatDbStrategy(metricsSystem, codeStorageStrategy);
      }
    }
  }

  @VisibleForTesting
  FlatDbMode deriveFlatDbStrategy(final SegmentedKeyValueStorage composedWorldStateStorage) {
    final FlatDbMode requestedFlatDbMode =
        dataStorageConfiguration.getUnstable().getBonsaiFullFlatDbEnabled()
            ? FlatDbMode.FULL
            : FlatDbMode.PARTIAL;

    final var existingTrieData =
        composedWorldStateStorage.get(TRIE_BRANCH_STORAGE, WORLD_ROOT_HASH_KEY).isPresent();

    var flatDbMode =
        FlatDbMode.fromVersion(
            composedWorldStateStorage
                .get(TRIE_BRANCH_STORAGE, FLAT_DB_MODE)
                .map(Bytes::wrap)
                .orElseGet(
                    () -> {
                      // if we do not have a db-supplied config for flatdb, derive it:
                      // default to partial if trie data exists, but the flat config does not,
                      // and default to the storage config otherwise

                      // TODO: temporarily hard code ARCHIVE mode for testing
                      var flatDbModeVal =
                          existingTrieData
                              ? FlatDbMode.ARCHIVE.getVersion()
                              : requestedFlatDbMode.getVersion();
                      // persist this config in the db
                      var setDbModeTx = composedWorldStateStorage.startTransaction();
                      setDbModeTx.put(
                          TRIE_BRANCH_STORAGE, FLAT_DB_MODE, flatDbModeVal.toArrayUnsafe());
                      setDbModeTx.commit();

                      return flatDbModeVal;
                    }));
    LOG.info("Bonsai flat db mode found {}", flatDbMode);

    return flatDbMode;
  }

  protected boolean deriveUseCodeStorageByHash(
      final SegmentedKeyValueStorage composedWorldStateStorage) {
    final boolean configCodeUsingHash =
        dataStorageConfiguration.getUnstable().getBonsaiCodeStoredByCodeHashEnabled();
    boolean codeUsingCodeByHash =
        detectCodeStorageByHash(composedWorldStateStorage)
            .map(
                dbCodeUsingHash -> {
                  if (dbCodeUsingHash != configCodeUsingHash) {
                    LOG.warn(
                        "Bonsai db is using code storage mode {} but config specifies mode {}. Using mode from database",
                        dbCodeUsingHash,
                        configCodeUsingHash);
                  }
                  return dbCodeUsingHash;
                })
            .orElse(configCodeUsingHash);
    LOG.info("Bonsai db mode with code stored using code hash enabled = {}", codeUsingCodeByHash);
    return codeUsingCodeByHash;
  }

  private Optional<Boolean> detectCodeStorageByHash(
      final SegmentedKeyValueStorage composedWorldStateStorage) {
    return composedWorldStateStorage.stream(CODE_STORAGE)
        .limit(1)
        .findFirst()
        .map(
            keypair ->
                CodeHashCodeStorageStrategy.isCodeHashValue(keypair.getKey(), keypair.getValue()));
  }

  public FlatDbStrategy getFlatDbStrategy(
      final SegmentedKeyValueStorage composedWorldStateStorage) {
    if (flatDbStrategy == null) {
      loadFlatDbStrategy(composedWorldStateStorage);
    }
    return flatDbStrategy;
  }

  public void upgradeToFullFlatDbMode(final SegmentedKeyValueStorage composedWorldStateStorage) {
    final SegmentedKeyValueStorageTransaction transaction =
        composedWorldStateStorage.startTransaction();
    LOG.info("setting FlatDbStrategy to FULL");
    transaction.put(
        TRIE_BRANCH_STORAGE, FLAT_DB_MODE, FlatDbMode.FULL.getVersion().toArrayUnsafe());
    transaction.commit();
    loadFlatDbStrategy(composedWorldStateStorage); // force reload of flat db reader strategy
  }

  public void downgradeToPartialFlatDbMode(
      final SegmentedKeyValueStorage composedWorldStateStorage) {
    final SegmentedKeyValueStorageTransaction transaction =
        composedWorldStateStorage.startTransaction();
    LOG.info("setting FlatDbStrategy to PARTIAL");
    transaction.put(
        TRIE_BRANCH_STORAGE, FLAT_DB_MODE, FlatDbMode.PARTIAL.getVersion().toArrayUnsafe());
    transaction.commit();
    loadFlatDbStrategy(composedWorldStateStorage); // force reload of flat db reader strategy
  }

  public FlatDbMode getFlatDbMode() {
    return flatDbMode;
  }

  public FlatDbStrategyProvider contextSafeClone() {
    FlatDbStrategyProvider copy =
        new FlatDbStrategyProvider(metricsSystem, dataStorageConfiguration);
    copy.flatDbStrategy = flatDbStrategy.contextSafeClone();
    copy.flatDbMode = flatDbMode;
    return copy;
  }
}
