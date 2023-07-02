package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerSelectViewModel
@Inject
constructor(
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _discoveredServersState = MutableStateFlow<DiscoveredServersState>(DiscoveredServersState.Loading)
    val discoveredServersState = _discoveredServersState.asStateFlow()

    // TODO get rid of livedata
    val servers = database.getAllServers()

    // TODO states may need to be merged / cleaned up
    sealed class UiState {
        data class Normal(val servers: List<Server>) : UiState()
        object Loading : UiState()
        data class Error(val message: Collection<UiText>) : UiState()
    }

    sealed class DiscoveredServersState {
        object Loading : DiscoveredServersState()
        data class Servers(val servers: List<DiscoveredServer>) : DiscoveredServersState()
    }

    private val _navigateToMain = MutableSharedFlow<Boolean>()
    val navigateToMain = _navigateToMain.asSharedFlow()

    private val discoveredServers = mutableListOf<DiscoveredServer>()

    init {
        getServers()
        discoverServers()
    }

    /**
     * Get Jellyfin servers stored in the database and emit them
     */
    private fun getServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val servers = database.getAllServersSync()
            _uiState.emit(UiState.Normal(servers))
        }
    }

    /**
     * Discover Jellyfin servers and emit them
     */
    private fun discoverServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val servers = jellyfinApi.jellyfin.discovery.discoverLocalServers()
            servers.collect { serverDiscoveryInfo ->
                discoveredServers.add(
                    DiscoveredServer(
                        serverDiscoveryInfo.id,
                        serverDiscoveryInfo.name,
                        serverDiscoveryInfo.address,
                    ),
                )
                _discoveredServersState.emit(DiscoveredServersState.Servers(ArrayList(discoveredServers)))
            }
        }
    }

    /**
     * Delete server from database
     *
     * @param server The server
     */
    fun deleteServer(server: Server) {
        viewModelScope.launch(Dispatchers.IO) {
            database.delete(server.id)
        }
    }

    fun connectToServer(server: Server) {
        viewModelScope.launch {
            val serverWithAddressesAndUsers = database.getServerWithAddressesAndUsers(server.id)!!
            val serverAddress = serverWithAddressesAndUsers.addresses.firstOrNull { it.id == server.currentServerAddressId } ?: return@launch
            val user = serverWithAddressesAndUsers.users.firstOrNull { it.id == server.currentUserId } ?: return@launch

            jellyfinApi.apply {
                api.baseUrl = serverAddress.address
                api.accessToken = user.accessToken
                userId = user.id
            }

            appPreferences.currentServer = server.id

            _navigateToMain.emit(true)
        }
    }
}
