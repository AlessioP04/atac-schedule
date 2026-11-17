package io.github.alessiop04.atacschedule.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.alessiop04.atacschedule.App
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime

@Composable
fun appFromContext(): App = LocalContext.current.applicationContext as App

/** Ora di Roma, aggiornata all'inizio di ogni minuto. */
fun nowFlow(): Flow<LocalDateTime> = flow {
    while (true) {
        val now = LocalDateTime.now(App.ROME)
        emit(now)
        delay((60 - now.second) * 1000L - now.nano / 1_000_000 + 50)
    }
}
