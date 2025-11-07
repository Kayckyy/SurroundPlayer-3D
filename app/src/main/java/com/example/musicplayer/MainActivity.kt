package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.musicplayer.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var musicService: MusicService? = null
    private var isBound = false
    private var isServiceStarting = false
    private var isFromNotification = false
    private var shouldRestorePlayback = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Permissão concedida!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissão necessária para acessar músicas", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            isServiceStarting = false
            notifyFragmentsServiceReady()
            checkStoragePermission()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isTaskRoot) {
            val intent = intent
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
                finish()
                return
            }
        }

        isFromNotification = intent.getBooleanExtra("FROM_NOTIFICATION", false)
        shouldRestorePlayback = intent.getBooleanExtra("RESTORE_PLAYBACK", false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        initializeService()
    }

    private fun initializeService() {
        musicService = MusicService.getInstance()
        if (musicService != null) {
            isBound = true
            notifyFragmentsServiceReady()

            if (isFromNotification) {
                switchToNowPlayingTab()
            }
        } else {
            // VERIFICAR SE HÁ ESTADO SALVO ANTES DE INICIAR SERVICE
            val prefs = getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            val wasPlaying = prefs.getBoolean("is_playing", false)
            val hasLastMusic = prefs.getString("last_music_path", "")?.isNotEmpty() == true

            if (wasPlaying && hasLastMusic) {
                startMusicService()
            }
            // SE NÃO HAVIA MÚSICA TOCANDO, NÃO INICIAR SERVICE AUTOMATICAMENTE
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when(position) {
                0 -> "Now Playing"
                1 -> "Explorar"
                2 -> "Settings"
                else -> null
            }
        }.attach()
    }

    private fun startMusicService() {
        if (isServiceStarting) return

        isServiceStarting = true
        val intent = Intent(this, MusicService::class.java)

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (shouldShowRequestPermissionRationale(permission)) {
                    Toast.makeText(this, "O app precisa de permissão para acessar suas músicas", Toast.LENGTH_LONG).show()
                }
            }
            requestPermissionLauncher.launch(permission)
        }
    }

    fun getMusicService(): MusicService? {
        if (!isBound) {
            musicService = MusicService.getInstance()
            if (musicService != null) {
                isBound = true
            }
        }
        return musicService
    }

    fun switchToNowPlayingTab() {
        binding.viewPager.currentItem = 0
    }

    private fun notifyFragmentsServiceReady() {
        supportFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is NowPlayingFragment -> fragment.onServiceReady()
                is FileExplorerFragment -> fragment.onServiceReady()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        isFromNotification = intent?.getBooleanExtra("FROM_NOTIFICATION", false) ?: false
        shouldRestorePlayback = intent?.getBooleanExtra("RESTORE_PLAYBACK", false) ?: false

        if (isFromNotification || shouldRestorePlayback) {
            switchToNowPlayingTab()

            // Se tem música e veio para restaurar reprodução, garantir que está pronto
            if (musicService?.hasMusic() == true) {
                notifyFragmentsServiceReady()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (!isBound && musicService == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                musicService = MusicService.getInstance()
                if (musicService == null) {
                    startMusicService()
                } else {
                    isBound = true
                    notifyFragmentsServiceReady()
                }
            }, 300)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}