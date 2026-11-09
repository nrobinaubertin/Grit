/*
 * Copyright (C) 2026  Niels Robin-Aubertin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shub39.grit.calendar.ui.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shub39.grit.caldav.AuthenticationException
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CategoryEntity
import com.shub39.grit.calendar.data.db.CollectionEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@Immutable
data class AccountForm(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
)

@Immutable
data class AccountState(
    val account: AccountEntity? = null,
    val collections: List<CollectionEntity> = emptyList(),
    /** Calendars that exist only on this device. */
    val localCalendars: List<CollectionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val form: AccountForm = AccountForm(),
    val syncing: Boolean = false,
    val loaded: Boolean = false,
)

@KoinViewModel
class AccountViewModel(
    private val store: CalendarStore,
    private val syncManager: CalendarSyncManager,
) : ViewModel() {
    private val form = MutableStateFlow(AccountForm())

    val state: StateFlow<AccountState> =
        combine(
                store.accountsFlow(),
                store.collectionsFlow(),
                store.categoriesFlow(),
                syncManager.running,
                form,
            ) { accounts, collections, categories, running, form ->
                val account = accounts.firstOrNull { !it.local }
                val local = accounts.firstOrNull { it.local }
                AccountState(
                    account = account,
                    collections = collections.filter { it.accountId == account?.id },
                    localCalendars = collections.filter { it.accountId == local?.id },
                    categories = categories,
                    form = form,
                    syncing = running,
                    loaded = true,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountState())

    fun updateForm(change: (AccountForm) -> AccountForm) =
        form.update { change(it).copy(error = null) }

    /** Logs in, saves the account with its calendars, and starts the first sync. */
    fun connect() {
        val current = form.value
        if (current.url.isBlank() || current.username.isBlank()) return
        form.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                val url = current.url.trim()
                val discovery = syncManager.discover(url, current.username.trim(), current.password)
                syncManager.addAccount(url, current.username.trim(), current.password, discovery)
                form.value = AccountForm()
                syncManager.syncAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationException) {
                form.update { it.copy(connecting = false, error = "Wrong user name or password") }
            } catch (e: Exception) {
                form.update {
                    it.copy(connecting = false, error = e.message ?: e::class.simpleName)
                }
            }
        }
    }

    fun changePassword(password: String) {
        val account = state.value.account ?: return
        viewModelScope.launch {
            syncManager.updatePassword(account.id, password)
            syncManager.syncAll()
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncManager.syncAll() }
    }

    fun setVisible(collectionId: Long, visible: Boolean) {
        viewModelScope.launch { syncManager.setVisible(collectionId, visible) }
    }

    fun setSyncEnabled(collectionId: Long, enabled: Boolean) {
        viewModelScope.launch { syncManager.setSyncEnabled(collectionId, enabled) }
    }

    /** Adds a calendar by URL. Calls [onResult] with an error text, or null on success. */
    fun addByUrl(url: String, onResult: (String?) -> Unit) {
        val account = state.value.account ?: return
        viewModelScope.launch {
            val error =
                try {
                    if (syncManager.addCollectionByUrl(account.id, url.trim()) == null) {
                        "No calendar found at this URL"
                    } else {
                        syncManager.syncAll()
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e::class.simpleName
                }
            onResult(error)
        }
    }

    fun createLocalCalendar(name: String) {
        viewModelScope.launch { store.createLocalCalendar(name.trim()) }
    }

    fun createCategory(name: String, color: String) {
        if (name.isBlank()) return
        viewModelScope.launch { store.createCategory(name, color) }
    }

    fun setCategoryColor(name: String, color: String) {
        viewModelScope.launch { store.setCategoryColor(name, color) }
    }

    fun deleteCategory(name: String) {
        viewModelScope.launch { store.deleteCategory(name) }
    }

    fun deleteLocalCalendar(collectionId: Long) {
        viewModelScope.launch { store.deleteLocalCalendar(collectionId) }
    }

    fun removeCollection(collectionId: Long) {
        viewModelScope.launch { syncManager.removeCollection(collectionId) }
    }

    fun removeAccount() {
        val account = state.value.account ?: return
        viewModelScope.launch { syncManager.removeAccount(account.id) }
    }
}
