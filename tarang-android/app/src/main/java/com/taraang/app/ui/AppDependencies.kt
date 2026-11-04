package com.taraang.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taraang.app.di.AppContainer
import com.taraang.app.di.AppContainerOwner

/** UI가 의존성을 얻는 유일한 경로. Application(AppContainerOwner)이 만든 컨테이너를 쓴다. */
@Composable
fun rememberAppContainer(): AppContainer {
    val app = LocalContext.current.applicationContext
    return remember(app) { (app as AppContainerOwner).container }
}

/** 컨테이너와 내비게이션 인자(SavedStateHandle)로 ViewModel을 만든다. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (container: AppContainer, handle: SavedStateHandle) -> VM,
): VM {
    val container = rememberAppContainer()
    return viewModel(
        factory = viewModelFactory {
            initializer { create(container, createSavedStateHandle()) }
        },
    )
}
