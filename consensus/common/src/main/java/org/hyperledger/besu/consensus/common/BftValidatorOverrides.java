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
package org.hyperledger.besu.consensus.common;

import org.hyperledger.besu.datatypes.Address;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** The Bft validator overrides. */
public class BftValidatorOverrides {

  private final Map<Long, List<Address>> overriddenValidators;
  private final NavigableMap<Long, List<Address>> timestampOverriddenValidators;

  /**
   * Instantiates a new BFT validator override with block-number-keyed overrides only.
   *
   * @param overriddenValidators the overridden validators, keyed by block number
   */
  public BftValidatorOverrides(final Map<Long, List<Address>> overriddenValidators) {
    this(overriddenValidators, Collections.emptyNavigableMap());
  }

  /**
   * Instantiates a new BFT validator override.
   *
   * @param overriddenValidators the overridden validators, keyed by block number
   * @param timestampOverriddenValidators the overridden validators, keyed by Unix timestamp
   */
  public BftValidatorOverrides(
      final Map<Long, List<Address>> overriddenValidators,
      final NavigableMap<Long, List<Address>> timestampOverriddenValidators) {
    this.overriddenValidators = overriddenValidators;
    this.timestampOverriddenValidators = new TreeMap<>(timestampOverriddenValidators);
  }

  /**
   * Gets for block.
   *
   * @param blockNumber the block number
   * @return validators address
   */
  public Optional<Collection<Address>> getForBlock(final long blockNumber) {
    return Optional.ofNullable(overriddenValidators.get(blockNumber));
  }

  /**
   * Returns whether any timestamp-keyed validator overrides are configured.
   *
   * @return true if at least one timestamp-based override exists
   */
  public boolean hasTimestampOverrides() {
    return !timestampOverriddenValidators.isEmpty();
  }

  /**
   * Gets the validator override for a fork timestamp crossed between a block and its parent. A
   * timestamp fork is applied to the first block whose parent had not yet reached the fork
   * timestamp but the block itself has - i.e. the override fires exactly once, on the boundary
   * block, and subsequent blocks inherit the set through the normal vote-tally propagation.
   *
   * @param parentTimestamp the timestamp of the parent block (use a value below every fork
   *     timestamp - e.g. -1 - when the block has no parent)
   * @param blockTimestamp the timestamp of the block being evaluated
   * @return the validators for the most recent fork timestamp in {@code (parentTimestamp,
   *     blockTimestamp]}, or empty if no fork timestamp was crossed
   */
  public Optional<Collection<Address>> getForTimestampBoundary(
      final long parentTimestamp, final long blockTimestamp) {
    final NavigableMap<Long, List<Address>> crossed =
        timestampOverriddenValidators.subMap(parentTimestamp, false, blockTimestamp, true);
    return crossed.isEmpty() ? Optional.empty() : Optional.of(crossed.get(crossed.lastKey()));
  }
}
