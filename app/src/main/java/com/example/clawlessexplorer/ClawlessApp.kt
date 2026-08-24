package com.example.clawlessexplorer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class ClawlessApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
