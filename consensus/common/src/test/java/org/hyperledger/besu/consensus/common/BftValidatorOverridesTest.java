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
package org.hyperledger.besu.consensus.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class BftValidatorOverridesTest {

  private static final List<Address> VALIDATORS_A =
      List.of(Address.fromHexString("0x1"), Address.fromHexString("0x2"));
  private static final List<Address> VALIDATORS_B =
      List.of(Address.fromHexString("0x3"), Address.fromHexString("0x4"));

  @Test
  void blockLookupIsExactMatch() {
    final BftValidatorOverrides overrides = new BftValidatorOverrides(Map.of(10L, VALIDATORS_A));

    assertThat(overrides.getForBlock(10L)).contains(VALIDATORS_A);
    assertThat(overrides.getForBlock(9L)).isEmpty();
    assertThat(overrides.getForBlock(11L)).isEmpty();
  }

  @Test
  void hasTimestampOverridesReflectsTimestampMap() {
    assertThat(new BftValidatorOverrides(Map.of(10L, VALIDATORS_A)).hasTimestampOverrides())
        .isFalse();

    final NavigableMap<Long, List<Address>> byTimestamp = new TreeMap<>();
    byTimestamp.put(1_700_000_000L, VALIDATORS_A);
    assertThat(new BftValidatorOverrides(Map.of(), byTimestamp).hasTimestampOverrides()).isTrue();
  }

  @Test
  void timestampBoundaryFiresOnlyOnTheCrossingBlock() {
    final NavigableMap<Long, List<Address>> byTimestamp = new TreeMap<>();
    byTimestamp.put(1_700_000_000L, VALIDATORS_A);
    final BftValidatorOverrides overrides = new BftValidatorOverrides(Map.of(), byTimestamp);

    // parent has not reached the fork, block has -> fires
    assertThat(overrides.getForTimestampBoundary(1_699_999_999L, 1_700_000_000L))
        .contains(VALIDATORS_A);
    // both parent and block already past the fork -> does not fire again (votes propagate instead)
    assertThat(overrides.getForTimestampBoundary(1_700_000_000L, 1_700_000_050L)).isEmpty();
    // both parent and block before the fork -> not yet
    assertThat(overrides.getForTimestampBoundary(1_699_999_000L, 1_699_999_500L)).isEmpty();
  }

  @Test
  void timestampBoundaryIsInclusiveOfBlockAndExclusiveOfParent() {
    final NavigableMap<Long, List<Address>> byTimestamp = new TreeMap<>();
    byTimestamp.put(1_700_000_000L, VALIDATORS_A);
    final BftValidatorOverrides overrides = new BftValidatorOverrides(Map.of(), byTimestamp);

    // fork timestamp exactly equals the block timestamp -> included
    assertThat(overrides.getForTimestampBoundary(1_699_999_000L, 1_700_000_000L))
        .contains(VALIDATORS_A);
    // fork timestamp exactly equals the parent timestamp -> already applied, excluded
    assertThat(overrides.getForTimestampBoundary(1_700_000_000L, 1_700_000_100L)).isEmpty();
  }

  @Test
  void whenMultipleForkTimestampsCrossInOneGapTheLatestWins() {
    final NavigableMap<Long, List<Address>> byTimestamp = new TreeMap<>();
    byTimestamp.put(1_700_000_000L, VALIDATORS_A);
    byTimestamp.put(1_700_000_500L, VALIDATORS_B);
    final BftValidatorOverrides overrides = new BftValidatorOverrides(Map.of(), byTimestamp);

    assertThat(overrides.getForTimestampBoundary(1_699_999_000L, 1_700_001_000L))
        .contains(VALIDATORS_B);
  }
}
