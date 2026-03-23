package com.receiptbridge.di

import android.content.Context
import androidx.room.Room
import com.receiptbridge.data.AppDatabase
import com.receiptbridge.data.PrintJobDao
import com.receiptbridge.data.PrinterProfileDao
import com.receiptbridge.data.SettingsDao
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "receipt-bridge-db"
        ).fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    fun provideSettingsDao(database: AppDatabase): SettingsDao {
        return database.settingsDao()
    }

    @Provides
    fun providePrinterProfileDao(database: AppDatabase): PrinterProfileDao {
        return database.printerProfileDao()
    }

    @Provides
    fun providePrintJobDao(database: AppDatabase): PrintJobDao {
        return database.printJobDao()
    }
}
