package dev.homescreenreader

import android.app.Application

class ReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The shared parser handles EPUB itself; MOBI/AZW3 go through the app's libmobi build.
        Ebooks.mobiUnpacker = NativeReader::unpackMobi
        applyTheme(ReadingState(this).appTheme)
    }

    companion object {
        /** Light, dark or the phone's setting, for every screen of the app. AppCompat redraws open screens when it changes. */
        fun applyTheme(theme: String) = androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
