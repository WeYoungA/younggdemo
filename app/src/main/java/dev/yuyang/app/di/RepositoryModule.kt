package dev.yuyang.app.di

import dev.yuyang.app.data.repository.FakeFeedRepository
import dev.yuyang.app.data.repository.FeedRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFeedRepository(impl: FakeFeedRepository): FeedRepository
}
