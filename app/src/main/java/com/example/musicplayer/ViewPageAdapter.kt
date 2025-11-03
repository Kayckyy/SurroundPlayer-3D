package com.example.musicplayer

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when(position) {
            0 -> NowPlayingFragment()
            1 -> FileExplorerFragment()
            2 -> SettingsFragment()
            else -> NowPlayingFragment()
        }
    }
}