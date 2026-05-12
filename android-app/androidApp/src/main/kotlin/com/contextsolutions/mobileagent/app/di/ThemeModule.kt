package com.contextsolutions.mobileagent.app.di

import android.content.Context
import com.contextsolutions.mobileagent.app.ui.theme.SharedPreferencesThemePreferences
import com.contextsolutions.mobileagent.app.ui.theme.ThemePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ThemeModule {

    @Provides
    @Singleton
    fun provideThemePreferences(
        @ApplicationContext context: Context,
    ): ThemePreferences = SharedPreferencesThemePreferences(context)
}
