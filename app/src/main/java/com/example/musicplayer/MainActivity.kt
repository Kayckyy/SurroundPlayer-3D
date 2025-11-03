package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

    // Sistema de permissões CORRIGIDO
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

            // Verificar permissão após conectar ao service
            checkStoragePermission()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicService = MusicService.getInstance()
        if (musicService != null) {
            isBound = true
            notifyFragmentsServiceReady()
        } else {
            startMusicService()
        }

        setupViewPager()
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

    // VERIFICAR PERMISSÃO CORRETAMENTE
    private fun checkStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            // Mostrar explicação antes de pedir permissão
            if (shouldShowRequestPermissionRationale(permission)) {
                Toast.makeText(this, "O app precisa de permissão para acessar suas músicas", Toast.LENGTH_LONG).show()
            }
            // Pedir permissão
            requestPermissionLauncher.launch(permission)
        }
    }

    fun getMusicService(): MusicService? {
        return musicService ?: MusicService.getInstance()
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

    override fun onResume() {
        super.onResume()
        if (!isBound && musicService == null) {
            musicService = MusicService.getInstance()
            if (musicService == null) {
                startMusicService()
            } else {
                isBound = true
                notifyFragmentsServiceReady()
            }
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