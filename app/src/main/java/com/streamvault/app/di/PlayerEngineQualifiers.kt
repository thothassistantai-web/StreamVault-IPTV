package com.streamvault.app.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainPlayerEngine

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuxiliaryPlayerEngine