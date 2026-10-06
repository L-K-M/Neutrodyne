// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class NavKeySerializersTest {
    private val json = Json { serializersModule = NavKeySerializers.module }

    @Test
    fun everyCanonicalKeyIsRegisteredExactlyOnce() {
        val collector = RegistrationCollector()
        NavKeySerializers.module.dumpTo(collector)

        assertEquals(expectedKeys, collector.registered)
    }

    @Test
    fun savedStateConfigurationUsesTheModule() {
        // The builder wraps the module, so compare behaviour: the configuration's module must
        // serialize every key exactly like NavKeySerializers.module.
        val configurationJson =
            Json {
                serializersModule = NavKeySerializers.savedStateConfiguration.serializersModule
            }

        for (key in sampleKeys) {
            val encoded = configurationJson.encodeToString(PolymorphicSerializer(NavKey::class), key)
            val decoded = configurationJson.decodeFromString(PolymorphicSerializer(NavKey::class), encoded)

            assertEquals(key, decoded, "Configuration round trip failed for $key")
        }
    }

    @Test
    fun everyKeyRoundTripsThroughJson() {
        for (key in sampleKeys) {
            val encoded = json.encodeToString(PolymorphicSerializer(NavKey::class), key)
            val decoded = json.decodeFromString(PolymorphicSerializer(NavKey::class), encoded)

            assertEquals(key, decoded, "Round trip failed for $key")
        }
    }

    // Collects the subclasses registered under NavKey; the module declares nothing else, so the
    // other collector members stay empty.
    private class RegistrationCollector : SerializersModuleCollector {
        val registered = mutableSetOf<KClass<*>>()

        override fun <Base : Any, Sub : Base> polymorphic(
            baseClass: KClass<Base>,
            subclass: KClass<Sub>,
            serializer: KSerializer<Sub>,
        ) {
            if (baseClass == NavKey::class) {
                registered += subclass
            }
        }

        override fun <T : Any> contextual(
            kClass: KClass<T>,
            provider: (List<KSerializer<*>>) -> KSerializer<*>,
        ) = Unit

        override fun <Base : Any> polymorphicDefaultSerializer(
            baseClass: KClass<Base>,
            defaultSerializer: (Base) -> SerializationStrategy<Base>?,
        ) = Unit

        override fun <Base : Any> polymorphicDefaultDeserializer(
            baseClass: KClass<Base>,
            defaultDeserializerProvider: (String?) -> DeserializationStrategy<Base>?,
        ) = Unit
    }

    private companion object {
        // The canonical key table (08 Screen inventory). Update this list with the module when a key
        // is added or removed; the registration test fails on any drift either way.
        val expectedKeys: Set<KClass<*>> =
            setOf(
                FeedsKey::class,
                LibraryKey::class,
                UpNextKey::class,
                DownloadsKey::class,
                DiscoverKey::class,
                PodcastKey::class,
                PodcastPreviewKey::class,
                PodcastSettingsKey::class,
                EpisodeKey::class,
                GroupEditKey::class,
                GroupsManageKey::class,
                GroupSettingsKey::class,
                AddToGroupsKey::class,
                AllGroupsKey::class,
                DirectoryKey::class,
                AddPodcastKey::class,
                ImportKey::class,
                BackupKey::class,
                ExportKey::class,
                SettingsHomeKey::class,
                SettingsKey::class,
                LicencesKey::class,
                InstallHelpKey::class,
                VerificationNoticeKey::class,
                KeyboardShortcutsKey::class,
                SyncSettingsKey::class,
                SyncSetupKey::class,
                SyncApproveKey::class,
                SyncDevicesKey::class,
                SyncHeldChangesKey::class,
                SyncDiagnosticsKey::class,
                DiagnosticsKey::class,
                SpeedKey::class,
                SleepTimerKey::class,
            )

        val sampleKeys: List<NavKey> =
            listOf(
                FeedsKey,
                LibraryKey,
                UpNextKey,
                DownloadsKey,
                DiscoverKey,
                PodcastKey(podcastId = 7L),
                PodcastPreviewKey(feedUrl = "https://example.com/feed.xml"),
                PodcastSettingsKey(podcastId = 7L),
                EpisodeKey(episodeId = 11L),
                GroupEditKey(groupId = null),
                GroupEditKey(groupId = 3L),
                GroupsManageKey,
                GroupSettingsKey(groupId = 3L),
                AddToGroupsKey(podcastIds = listOf(1L, 2L)),
                AllGroupsKey,
                DirectoryKey(query = "rock", genreId = null),
                DirectoryKey(query = "rock", genreId = "1301"),
                AddPodcastKey(input = null),
                AddPodcastKey(input = "feed:https://example.com/feed.xml"),
                ImportKey(sessionId = "session-1"),
                BackupKey,
                ExportKey(groupId = null),
                ExportKey(groupId = 5L),
                SettingsHomeKey,
                SettingsKey(page = SettingsPage.ABOUT),
                SettingsKey(page = SettingsPage.UPDATES, openRelease = true),
                LicencesKey,
                InstallHelpKey(),
                InstallHelpKey(section = "ALLOW"),
                VerificationNoticeKey,
                KeyboardShortcutsKey,
                SyncSettingsKey,
                SyncSetupKey(),
                SyncSetupKey(serverUrl = "https://sync.example.com"),
                SyncApproveKey(),
                SyncApproveKey(userCode = "ABCD-1234"),
                SyncDevicesKey,
                SyncHeldChangesKey(id = 9L),
                SyncDiagnosticsKey,
                DiagnosticsKey,
                SpeedKey,
                SleepTimerKey,
            )
    }
}
