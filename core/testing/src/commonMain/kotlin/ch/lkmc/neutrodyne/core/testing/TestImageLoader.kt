// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImage
import coil3.test.FakeImageLoaderEngine

private const val FAKE_IMAGE_SIZE = 64

/**
 * Points Coil's process-wide [SingletonImageLoader] at a [FakeImageLoaderEngine] that answers
 * every request with a fixed [FakeImage] — screen tests never touch the network (09's
 * deterministic-only rule). [images] overrides the default square for the request data that
 * matches each key (for example a URL string), so tests can control the loaded size.
 * `setUnsafe` makes re-installs deterministic even after an earlier test has materialised the
 * singleton; call it from each UI test class' set-up.
 */
public fun installFakeImageLoader(images: Map<Any, FakeImage> = emptyMap()) {
    SingletonImageLoader.setUnsafe { context ->
        ImageLoader
            .Builder(context)
            .components {
                add(
                    FakeImageLoaderEngine
                        .Builder()
                        .apply { images.forEach { (data, image) -> intercept(data, image) } }
                        .default(FakeImage(FAKE_IMAGE_SIZE, FAKE_IMAGE_SIZE))
                        .build(),
                )
            }.build()
    }
}
