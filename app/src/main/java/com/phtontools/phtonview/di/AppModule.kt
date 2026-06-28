package com.phtontools.phtonview.di

import com.phtontools.phtonview.connection.CameraConnection
import com.phtontools.phtonview.connection.WifiCameraConnection
import com.phtontools.phtonview.data.repository.CameraRepository
import com.phtontools.phtonview.data.repository.CameraRepositoryImpl
import com.phtontools.phtonview.usb.UsbCameraConnection
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hilt dependency injection module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindUsbCameraConnection(
        impl: UsbCameraConnection
    ): CameraConnection

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindWifiCameraConnection(
        impl: WifiCameraConnection
    ): CameraConnection

    @Binds
    @Singleton
    abstract fun bindCameraRepository(
        impl: CameraRepositoryImpl
    ): CameraRepository
}
