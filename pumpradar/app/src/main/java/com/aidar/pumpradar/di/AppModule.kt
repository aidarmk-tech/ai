package com.aidar.pumpradar.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.aidar.pumpradar.data.local.AppEventDao
import com.aidar.pumpradar.data.local.ClusterDao
import com.aidar.pumpradar.data.local.MIGRATION_1_2
import com.aidar.pumpradar.data.local.MIGRATION_2_3
import com.aidar.pumpradar.data.local.MIGRATION_3_4
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.PumpRadarDatabase
import com.aidar.pumpradar.data.local.ShadowSignalDao
import com.aidar.pumpradar.data.local.SignalDao
import com.aidar.pumpradar.data.local.SignalTrajectoryDao
import com.aidar.pumpradar.data.local.TrainingSnapshotDao
import com.aidar.pumpradar.data.preferences.dataStore
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
    fun provideDatabase(@ApplicationContext context: Context): PumpRadarDatabase =
        Room.databaseBuilder(context, PumpRadarDatabase::class.java, "pumpradar.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)   // не destructive
            .build()

    @Provides
    fun provideSignalDao(db: PumpRadarDatabase): SignalDao = db.signalDao()

    @Provides
    fun provideOutcomeDao(db: PumpRadarDatabase): OutcomeDao = db.outcomeDao()

    @Provides
    fun provideAppEventDao(db: PumpRadarDatabase): AppEventDao = db.appEventDao()

    @Provides
    fun provideClusterDao(db: PumpRadarDatabase): ClusterDao = db.clusterDao()

    @Provides
    fun provideTrainingSnapshotDao(db: PumpRadarDatabase): TrainingSnapshotDao =
        db.trainingSnapshotDao()

    @Provides
    fun provideSignalTrajectoryDao(db: PumpRadarDatabase): SignalTrajectoryDao =
        db.signalTrajectoryDao()

    @Provides
    fun provideShadowSignalDao(db: PumpRadarDatabase): ShadowSignalDao =
        db.shadowSignalDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore
}
