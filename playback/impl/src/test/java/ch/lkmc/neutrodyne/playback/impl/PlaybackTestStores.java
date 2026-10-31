// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl;

import ch.lkmc.neutrodyne.core.common.Clock;
import ch.lkmc.neutrodyne.core.data.playback.PlaybackMediaLookupImpl;
import ch.lkmc.neutrodyne.core.data.playback.PositionWriter;
import ch.lkmc.neutrodyne.core.data.playback.QueueRepositoryImpl;
import ch.lkmc.neutrodyne.core.data.playback.SessionWriter;
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase;
import ch.lkmc.neutrodyne.core.domain.OrderKeys;
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore;
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup;
import ch.lkmc.neutrodyne.core.domain.PositionStore;
import ch.lkmc.neutrodyne.core.domain.QueueRepository;
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource;

/** Access real internal store implementations without widening their production Kotlin APIs. */
final class PlaybackTestStores {
  static PlaySessionStore sessions(NeutrodyneDatabase db, OrderKeys keys, YouTubeCapabilitiesSource caps) {
    return new SessionWriter(db, keys, caps);
  }

  static PositionStore positions(NeutrodyneDatabase db) {
    return new PositionWriter(db, new ch.lkmc.neutrodyne.core.testing.TestClock());
  }

  static PlaybackMediaLookup media(NeutrodyneDatabase db) {
    return new PlaybackMediaLookupImpl(db);
  }

  static QueueRepository queue(NeutrodyneDatabase db, PlaySessionStore sessions, OrderKeys keys, Clock clock, YouTubeCapabilitiesSource caps) {
    return new QueueRepositoryImpl(db, sessions, keys, clock, caps);
  }

  private PlaybackTestStores() {}
}
