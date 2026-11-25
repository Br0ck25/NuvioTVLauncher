package com.nuvio.tv.core.di

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.music.GeminiKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object MusicGeminiModule {
    @Provides
    @IntoSet
    fun credentialStore(store: GeminiKeyStore): ProfileScopedCredentialStore = store
}
