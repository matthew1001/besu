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
package org.hyperledger.besu.consensus.common.validator.blockbased;

import static com.google.common.base.Preconditions.checkNotNull;

import org.hyperledger.besu.consensus.common.BftValidatorOverrides;
import org.hyperledger.besu.consensus.common.BlockInterface;
import org.hyperledger.besu.consensus.common.EpochManager;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;

import java.util.Collection;
import java.util.Optional;

class ForkingVoteTallyCache extends VoteTallyCache {

  private final Blockchain blockchain;
  private final BftValidatorOverrides validatorOverrides;

  public ForkingVoteTallyCache(
      final Blockchain blockchain,
      final VoteTallyUpdater voteTallyUpdater,
      final EpochManager epochManager,
      final BlockInterface blockInterface,
      final BftValidatorOverrides validatorOverrides) {
    super(blockchain, voteTallyUpdater, epochManager, blockInterface);
    checkNotNull(validatorOverrides);
    this.blockchain = blockchain;
    this.validatorOverrides = validatorOverrides;
  }

  @Override
  protected VoteTally getValidatorsAfter(final BlockHeader header) {
    final long nextBlockNumber = header.getNumber() + 1L;

    final Optional<Collection<Address>> blockOverride =
        validatorOverrides.getForBlock(nextBlockNumber);
    if (blockOverride.isPresent()) {
      return new VoteTally(blockOverride.get());
    }

    // A timestamp-based validator transition fires on the first block whose header crosses the
    // fork timestamp (relative to its parent).
    if (validatorOverrides.hasTimestampOverrides()) {
      final long parentTimestamp =
          blockchain
              .getBlockHeader(header.getParentHash())
              .map(BlockHeader::getTimestamp)
              .orElse(-1L);
      final Optional<Collection<Address>> timestampOverride =
          validatorOverrides.getForTimestampBoundary(parentTimestamp, header.getTimestamp());
      if (timestampOverride.isPresent()) {
        return new VoteTally(timestampOverride.get());
      }
    }

    return super.getValidatorsAfter(header);
  }
}
