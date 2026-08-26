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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider.createInMemoryBlockchain;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.common.BftValidatorOverrides;
import org.hyperledger.besu.consensus.common.EpochManager;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.AddressHelpers;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.google.common.collect.Lists;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

public class ForkingVoteTallyCacheTest extends VoteTallyCacheTestBase {

  @Test
  public void validatorFromForkAreReturnedRatherThanPriorBlock() {
    final List<Address> forkedValidators =
        Lists.newArrayList(Address.fromHexString("5"), Address.fromHexString("6"));
    final Map<Long, List<Address>> forkingValidatorMap = new HashMap<>();
    forkingValidatorMap.put(3L, forkedValidators);

    final VoteTallyUpdater tallyUpdater = mock(VoteTallyUpdater.class);
    final ForkingVoteTallyCache cache =
        new ForkingVoteTallyCache(
            blockChain,
            tallyUpdater,
            new EpochManager(30_000),
            blockInterface,
            new BftValidatorOverrides(forkingValidatorMap));

    final VoteTally result = cache.getVoteTallyAfterBlock(block_2.getHeader());

    assertThat(result.getValidators()).containsExactlyElementsOf(forkedValidators);
  }

  @Test
  public void emptyForkingValidatorMapResultsInValidatorsBeingReadFromPreviousHeader() {
    final VoteTallyUpdater tallyUpdater = mock(VoteTallyUpdater.class);
    final ForkingVoteTallyCache cache =
        new ForkingVoteTallyCache(
            blockChain,
            tallyUpdater,
            new EpochManager(30_000),
            blockInterface,
            new BftValidatorOverrides(new HashMap<>()));

    final VoteTally result = cache.getVoteTallyAfterBlock(block_2.getHeader());

    assertThat(result.getValidators()).containsExactlyElementsOf(validators);
  }

  @Test
  public void validatorsInForkUsedIfForkDirectlyFollowsEpoch() {
    final List<Address> forkedValidators =
        Lists.newArrayList(Address.fromHexString("5"), Address.fromHexString("6"));
    final Map<Long, List<Address>> forkingValidatorMap = new HashMap<>();
    forkingValidatorMap.put(3L, forkedValidators);

    final VoteTallyUpdater tallyUpdater = mock(VoteTallyUpdater.class);
    final ForkingVoteTallyCache cache =
        new ForkingVoteTallyCache(
            blockChain,
            tallyUpdater,
            new EpochManager(2L),
            blockInterface,
            new BftValidatorOverrides(forkingValidatorMap));

    final VoteTally result = cache.getVoteTallyAfterBlock(block_2.getHeader());

    assertThat(result.getValidators()).containsExactlyElementsOf(forkedValidators);
  }

  @Test
  public void atHeadApiOperatesIdenticallyToUnderlyingApi() {
    final List<Address> forkedValidators =
        Lists.newArrayList(Address.fromHexString("5"), Address.fromHexString("6"));
    final Map<Long, List<Address>> forkingValidatorMap = new HashMap<>();
    forkingValidatorMap.put(3L, forkedValidators);

    final VoteTallyUpdater tallyUpdater = mock(VoteTallyUpdater.class);
    final ForkingVoteTallyCache cache =
        new ForkingVoteTallyCache(
            blockChain,
            tallyUpdater,
            new EpochManager(30_000L),
            blockInterface,
            new BftValidatorOverrides(forkingValidatorMap));

    final VoteTally result = cache.getVoteTallyAtHead();

    assertThat(result.getValidators()).containsExactlyElementsOf(forkedValidators);
  }

  @Test
  public void timestampOverrideAppliedAtBlockThatCrossesForkTimestamp() {
    final List<Address> forkedValidators =
        Lists.newArrayList(Address.fromHexString("5"), Address.fromHexString("6"));
    final NavigableMap<Long, List<Address>> byTimestamp = new TreeMap<>();
    byTimestamp.put(150L, forkedValidators);

    // Build a chain with controlled timestamps: genesis@0 (epoch block), b1@100, b2@200.
    final BlockHeaderTestFixture builder = new BlockHeaderTestFixture();
    builder.extraData(Bytes.wrap(new byte[32])).coinbase(AddressHelpers.ofValue(0));

    final Block genesis = timedBlock(builder, 0, Hash.ZERO, 0);
    final MutableBlockchain chain = createInMemoryBlockchain(genesis);
    final Block b1 = timedBlock(builder, 1, genesis.getHeader().getHash(), 100);
    final Block b2 = timedBlock(builder, 2, b1.getHeader().getHash(), 200);
    chain.appendBlock(b1, Collections.emptyList());
    chain.appendBlock(b2, Collections.emptyList());

    when(blockInterface.validatorsInBlock(any())).thenReturn(validators);

    final ForkingVoteTallyCache cache =
        new ForkingVoteTallyCache(
            chain,
            mock(VoteTallyUpdater.class),
            new EpochManager(30_000L),
            blockInterface,
            new BftValidatorOverrides(new HashMap<>(), byTimestamp));

    // Fork timestamp 150 is crossed between b1 (100) and b2 (200): the set after b2 is the
    // override.
    assertThat(cache.getVoteTallyAfterBlock(b2.getHeader()).getValidators())
        .containsExactlyElementsOf(forkedValidators);

    // Before the crossing (after b1), the base validators still apply.
    assertThat(cache.getVoteTallyAfterBlock(b1.getHeader()).getValidators())
        .containsExactlyElementsOf(validators);
  }

  private Block timedBlock(
      final BlockHeaderTestFixture builder,
      final long number,
      final Hash parentHash,
      final long timestamp) {
    final BlockHeader header =
        builder.number(number).parentHash(parentHash).timestamp(timestamp).buildHeader();
    return new Block(header, new BlockBody(Collections.emptyList(), Collections.emptyList()));
  }
}
