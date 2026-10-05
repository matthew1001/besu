/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.consensus.qbft.adaptor;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers the processing outputs of recently validated proposal blocks so that the post-commit
 * import of the same block can append it to the chain without re-executing every transaction.
 *
 * <p>Entries are keyed by block hash. A round-0 proposal and its sealed on-chain counterpart hash
 * identically (commit seals and a zero round number are both excluded from the hashed extra data),
 * and the world state persisted when the proposal was executed is stored under that same hash, so a
 * hit here guarantees the import can reuse both. A block committed after a round change hashes
 * differently from the round-0 form that may have been validated, so it misses this cache and falls
 * back to a full re-executing import.
 */
public class ValidatedBlockProcessingCache {

  // A height only circulates a handful of candidate blocks before one commits, so a few entries
  // are enough; the bound only guards against unbounded growth during long round-change storms.
  private static final int MAX_ENTRIES = 8;

  /**
   * The processing outputs required to import a block without re-executing it.
   *
   * @param receipts the transaction receipts produced by executing the block
   * @param blockAccessList the block access list produced by executing the block, if any
   */
  public record ValidatedBlockOutputs(
      List<TransactionReceipt> receipts, Optional<BlockAccessList> blockAccessList) {}

  private final Map<Hash, ValidatedBlockOutputs> outputsByHash =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Hash, ValidatedBlockOutputs> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  /** Creates an empty cache. */
  public ValidatedBlockProcessingCache() {}

  /**
   * Stores the processing outputs of a successfully validated block.
   *
   * @param blockHash the hash of the validated block
   * @param outputs the outputs produced by executing the block
   */
  public synchronized void put(final Hash blockHash, final ValidatedBlockOutputs outputs) {
    outputsByHash.put(blockHash, outputs);
  }

  /**
   * Retrieves the processing outputs of a previously validated block.
   *
   * @param blockHash the hash of the block to look up
   * @return the outputs produced when the block was validated, if present
   */
  public synchronized Optional<ValidatedBlockOutputs> get(final Hash blockHash) {
    return Optional.ofNullable(outputsByHash.get(blockHash));
  }
}
