package com.getfirepit.core.data

import javax.inject.Qualifier

/** Outlives any screen: the radio link and its inbound pump must not die with a ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
