package co.ke.kumea.di

import android.content.Context
import androidx.room.Room
import co.ke.kumea.data.local.AgentDao
import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FieldDao
import co.ke.kumea.data.local.KumeaDatabase
import co.ke.kumea.data.local.NoteDao
import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.SyncConflictDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideKumeaDatabase(
        @ApplicationContext context: Context,
    ): KumeaDatabase = Room.databaseBuilder(
        context,
        KumeaDatabase::class.java,
        DATABASE_NAME,
    ).fallbackToDestructiveMigration()
        .build()

    @Provides
    @Singleton
    fun provideAgentDao(database: KumeaDatabase): AgentDao = database.agentDao()

    @Provides
    @Singleton
    fun provideFarmDao(database: KumeaDatabase): FarmDao = database.farmDao()

    @Provides
    @Singleton
    fun provideFieldDao(database: KumeaDatabase): FieldDao = database.fieldDao()

    @Provides
    @Singleton
    fun provideNoteDao(database: KumeaDatabase): NoteDao = database.noteDao()

    @Provides
    @Singleton
    fun provideOrderDao(database: KumeaDatabase): OrderDao = database.orderDao()

    @Provides
    @Singleton
    fun provideSyncConflictDao(database: KumeaDatabase): SyncConflictDao = database.syncConflictDao()

    private const val DATABASE_NAME = "kumea.db"
}
