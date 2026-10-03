package com.pocketpad.di

import android.content.Context
import androidx.room.Room
import com.pocketpad.data.profile.PocketPadDatabase
import com.pocketpad.data.profile.ProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PocketPadDatabase =
        Room.databaseBuilder(context, PocketPadDatabase::class.java, "pocketpad.db")
            .build()

    @Provides
    fun provideProfileDao(database: PocketPadDatabase): ProfileDao = database.profileDao()

}
