package net.ip.rerouter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ip.rerouter.model.AppInfo
import net.ip.rerouter.model.AppState
import net.ip.rerouter.model.NetInterface
import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.net.AppRepository
import net.ip.rerouter.net.InterfaceRepository
import net.ip.rerouter.net.RoutingEngine
import net.ip.rerouter.net.StateStore
import net.ip.rerouter.root.RootShell
import java.util.UUID

data class UiState(
    val hasRoot: Boolean? = null,
    val interfaces: List<NetInterface> = emptyList(),
    val rules: List<RouteRule> = emptyList(),
    val installedApps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val interfaceRepo = InterfaceRepository()
    private val appRepo = AppRepository(application)
    private val routingEngine = RoutingEngine()
    private val stateStore = StateStore(application)
    private val refreshMutex = Mutex()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var appState = AppState()

    init {
        viewModelScope.launch {
            val rootOk = RootShell.isRootAvailable()
            _uiState.value = _uiState.value.copy(hasRoot = rootOk)

            if (!rootOk) return@launch

            appState = stateStore.load()
            _uiState.value = _uiState.value.copy(rules = appState.rules)

            refreshInterfaces(showLoading = true)
            loadApps()

            // Android networking can create/remove interfaces after the app is
            // already open (for example when the hotspot is enabled). Poll the
            // kernel instead of relying on the list being static.
            launch {
                while (isActive) {
                    delay(INTERFACE_REFRESH_MS)
                    refreshInterfaces(showLoading = false)
                }
            }
        }
    }

    /**
     * Re-reads live kernel interfaces. This deliberately uses ip(8), not the
     * Android ConnectivityManager list, because root routing can see interfaces
     * such as wlan1/tun1 that are not represented as normal app networks.
     */
    fun refreshInterfaces(showLoading: Boolean = false) {
        viewModelScope.launch {
            refreshMutex.withLock {
                if (showLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }

                val created = appState.createdInterfaces.toSet()
                val list = interfaceRepo.listInterfaces(created)

                _uiState.value = _uiState.value.copy(
                    interfaces = list,
                    isLoading = if (showLoading) false else _uiState.value.isLoading
                )
            }
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = appRepo.listInstalledApps()
            _uiState.value = _uiState.value.copy(installedApps = apps)
        }
    }

    fun createInterface(name: String, isDummy: Boolean, ipCidr: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val ok = if (isDummy) {
                interfaceRepo.createDummyInterface(name, ipCidr)
            } else {
                interfaceRepo.createTunInterface(name, ipCidr)
            }

            if (ok) {
                appState = appState.copy(createdInterfaces = appState.createdInterfaces + name)
                stateStore.save(appState)
                refreshInterfaces(showLoading = false)
                _uiState.value = _uiState.value.copy(isLoading = false)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to create interface $name"
                )
            }
        }
    }

    fun removeInterface(name: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val affected = appState.rules.filter {
                it.fromInterface == name || it.toInterface == name
            }
            affected.forEach { routingEngine.removeRule(it) }

            val ok = interfaceRepo.removeInterface(name)
            if (ok) {
                appState = appState.copy(
                    createdInterfaces = appState.createdInterfaces - name,
                    rules = appState.rules - affected.toSet()
                )
                stateStore.save(appState)
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to remove interface $name"
                )
            }

            refreshInterfaces(showLoading = false)
            _uiState.value = _uiState.value.copy(
                rules = appState.rules,
                isLoading = false
            )
        }
    }

    fun toggleInterfaceState(name: String, up: Boolean) {
        viewModelScope.launch {
            val ok = interfaceRepo.setInterfaceState(name, up)
            if (!ok) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to set $name ${if (up) "up" else "down"}"
                )
            }
            refreshInterfaces(showLoading = false)
        }
    }

    fun addRule(
        fromInterface: String,
        toInterface: String,
        excludedUids: Set<Int>,
        useMasquerade: Boolean
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Refresh immediately before applying a rule. This matters when a
            // hotspot/tunnel appeared seconds ago and the UI just discovered it.
            val liveInterfaces = interfaceRepo.listInterfaces(appState.createdInterfaces.toSet())
            val liveNames = liveInterfaces.map { it.name }.toSet()

            if (fromInterface !in liveNames || toInterface !in liveNames) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Interface disappeared: $fromInterface → $toInterface"
                )
                refreshInterfaces(showLoading = false)
                return@launch
            }

            val tableId = appState.nextTableId
            val rule = RouteRule(
                id = UUID.randomUUID().toString().take(8),
                fromInterface = fromInterface,
                toInterface = toInterface,
                tableId = tableId,
                excludedUids = excludedUids,
                useMasquerade = useMasquerade
            )

            val ok = routingEngine.applyRule(rule)
            if (ok) {
                appState = appState.copy(
                    rules = appState.rules + rule,
                    nextTableId = if (tableId >= 252) 100 else tableId + 1
                )
                stateStore.save(appState)
                _uiState.value = _uiState.value.copy(
                    rules = appState.rules,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to apply route: $fromInterface → $toInterface"
                )
            }
        }
    }

    fun removeRule(ruleId: String) {
        viewModelScope.launch {
            val rule = appState.rules.firstOrNull { it.id == ruleId } ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)

            val ok = routingEngine.removeRule(rule)
            if (ok) {
                appState = appState.copy(rules = appState.rules - rule)
                stateStore.save(appState)
                _uiState.value = _uiState.value.copy(
                    rules = appState.rules,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to remove route ${rule.fromInterface} → ${rule.toInterface}"
                )
            }
        }
    }

    fun toggleRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            val rule = appState.rules.firstOrNull { it.id == ruleId } ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)

            val ok = if (enabled) {
                routingEngine.applyRule(rule)
            } else {
                routingEngine.removeRule(rule)
            }

            if (!ok) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to ${if (enabled) "enable" else "disable"} route"
                )
                return@launch
            }

            val updated = rule.copy(enabled = enabled)
            appState = appState.copy(
                rules = appState.rules.map { if (it.id == ruleId) updated else it }
            )
            stateStore.save(appState)
            _uiState.value = _uiState.value.copy(
                rules = appState.rules,
                isLoading = false
            )
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            routingEngine.resetAll(appState.rules, appState.createdInterfaces)
            appState = AppState()
            stateStore.save(appState)
            refreshInterfaces(showLoading = false)
            _uiState.value = _uiState.value.copy(
                rules = emptyList(),
                isLoading = false
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private companion object {
        const val INTERFACE_REFRESH_MS = 2000L
    }
}
