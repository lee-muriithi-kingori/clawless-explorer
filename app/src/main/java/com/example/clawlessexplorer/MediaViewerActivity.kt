package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.clawlessexplorer.databinding.ActivityMediaViewerBinding
import java.io.File
import java.util.concurrent.TimeUnit

class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private var mediaPlayer: MediaPlayer? = null
    private var mediaType: String = ""
    private var filePath: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var isVideo: Boolean = false
    private var isPlaying: Boolean = false
    private var hideControlsRunnable: Runnable? = null

    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    binding.seekBar.progress = mp.currentPosition
                    binding.timeCurrent.text = formatTime(mp.currentPosition)
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        filePath = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: ""
        isVideo = mediaType == "video"

        val file = File(filePath)
        if (!file.exists()) { finish(); return }

        binding.titleText.text = file.name

        binding.btnBack.setOnClickListener { finish() }

        if (isVideo) {
            setupVideoMode()
        } else {
            setupAudioMode()
        }

        setupControls()
    }

    private fun setupVideoMode() {
        binding.surfaceView.visibility = View.VISIBLE
        binding.audioContainer.visibility = View.GONE

        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startPlayback(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pausePlayback()
            }
        })
    }

    private fun setupAudioMode() {
        binding.surfaceView.visibility = View.GONE
        binding.audioContainer.visibility = View.VISIBLE
        binding.topOverlay.visibility = View.GONE
        binding.controlsContainer.setBackgroundColor(android.graphics.Color.parseColor("#1A1B26"))

        binding.audioTitle.text = File(filePath).name

        startAudioPlayback()
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnPlayPauseToolbar.setOnClickListener { togglePlayback() }
        binding.btnRewind.setOnClickListener { seekBy(-10000) }
        binding.btnForward.setOnClickListener { seekBy(10000) }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    binding.timeCurrent.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startPlayback(holder: SurfaceHolder? = null) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                if (holder != null) {
                    setDisplay(holder)
                }
                setOnPreparedListener { mp ->
                    binding.seekBar.max = mp.duration
                    binding.timeTotal.text = formatTime(mp.duration)
                    mp.start()
                    this@MediaViewerActivity.isPlaying = true
                    updatePlayPauseIcons()
                    handler.post(updateSeekBar)
                    if (isVideo) scheduleHideControls()
                }
                setOnCompletionListener {
                    this@MediaViewerActivity.isPlaying = false
                    updatePlayPauseIcons()
                    handler.removeCallbacks(updateSeekBar)
                    binding.controlsContainer.visibility = View.VISIBLE
                    binding.topOverlay.visibility = View.VISIBLE
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            finish()
        }
    }

    private fun startAudioPlayback() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    binding.seekBar.max = mp.duration
                    binding.timeTotal.text = formatTime(mp.duration)
                    mp.start()
                    this@MediaViewerActivity.isPlaying = true
                    updatePlayPauseIcons()
                    handler.post(updateSeekBar)
                }
                setOnCompletionListener {
                    this@MediaViewerActivity.isPlaying = false
                    updatePlayPauseIcons()
                    handler.removeCallbacks(updateSeekBar)
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            finish()
        }
    }

    private fun togglePlayback() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                pausePlayback()
            } else {
                mp.start()
                isPlaying = true
                updatePlayPauseIcons()
                handler.post(updateSeekBar)
                if (isVideo) scheduleHideControls()
            }
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
                updatePlayPauseIcons()
                handler.removeCallbacks(updateSeekBar)
                if (isVideo) {
                    binding.controlsContainer.visibility = View.VISIBLE
                    binding.topOverlay.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun seekBy(ms: Int) {
        mediaPlayer?.let { mp ->
            val newPos = (mp.currentPosition + ms).coerceIn(0, mp.duration)
            mp.seekTo(newPos)
            binding.seekBar.progress = newPos
            binding.timeCurrent.text = formatTime(newPos)
        }
    }

    private fun updatePlayPauseIcons() {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPause.setImageResource(icon)
        binding.btnPlayPauseToolbar.setImageResource(icon)
    }

    private fun scheduleHideControls() {
        hideControlsRunnable?.let { handler.removeCallbacks(it) }
        hideControlsRunnable = Runnable {
            if (isPlaying) {
                binding.controlsContainer.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { binding.controlsContainer.visibility = View.INVISIBLE }
                    .start()
                binding.topOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .start()
            }
        }
        handler.postDelayed(hideControlsRunnable!!, 4000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isVideo) {
            binding.controlsContainer.visibility = View.VISIBLE
            binding.controlsContainer.alpha = 1f
            binding.topOverlay.visibility = View.VISIBLE
            binding.topOverlay.alpha = 1f
            if (isPlaying) scheduleHideControls()
        }
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun formatTime(ms: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    companion object {
        const val EXTRA_PATH = "extra_media_path"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"

        fun intent(context: Context, path: String, mediaType: String): Intent =
            Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_MEDIA_TYPE, mediaType)
            }
    }
}
