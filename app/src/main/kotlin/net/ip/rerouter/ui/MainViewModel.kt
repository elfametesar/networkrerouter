package net.ip.rerouter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val hasRoot: Boolean? = null, // null = not checked yet
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

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var appState = AppState()

    init {
        viewModelScope.launch {
            val rootOk = RootShell.isRootAvailable()
            _uiState.value = _uiState.value.copy(hasRoot = rootOk)
            if (rootOk) {
                appState = stateStore.load()
                _uiState.value = _uiState.value.copy(rules = appState.rules)
                refreshInterfaces()
                loadApps()
            }
        }
    }

    fun refreshInterfaces() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val created = appState.createdInterfaces.toSet()
            val list = interfaceRepo.listInterfaces(created)
            _uiState.value = _uiState.value.copy(interfaces = list, isLoading = false)
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = appRepo.listInstalledApps()
            _uiState.value = _uiState.value.copy(installedApps = apps)
        }
    }

    fun createInterface(name: String, isDummy: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val ok = if (isDummy) interfaceRepo.createDummyInterface(name)
                      else interfaceRepo.createTunInterface(name)
            if (ok) {
                appState = appState.copy(createdInterfaces = appState.createdInterfaces + name)
                stateStore.save(appState)
                refreshInterfaces()
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
            // Also tear down any rules that reference this interface first.
            val affected = appState.rules.filter { it.fromInterface == name || it.toInterface == name }
            affected.forEach { routingEngine.removeRule(it) }

            val ok = interfaceRepo.removeInterface(name)
            if (ok) {
                appState = appState.copy(
                    createdInterfaces = appState.createdInterfaces - name,
                    rules = appState.rules - affected.toSet()
                )
                stateStore.save(appState)
            }
            refreshInterfaces()
            _uiState.value = _uiState.value.copy(rules = appState.rules)
        }
    }

    fun toggleInterfaceState(name: String, up: Boolean) {
        viewModelScope.launch {
            interfaceRepo.setInterfaceState(name, up)
            refreshInterfaces()
        }
    }

    fun addRule(fromInterface: String, toInterface: String, excludedUids: Set<Int>, useMasquerade: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
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
                _uiState.value = _uiState.value.copy(rules = appState.rules, isLoading = false)
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
            routingEngine.removeRule(rule)
            appState = appState.copy(rules = appState.rules - rule)
            stateStore.save(appState)
            _uiState.value = _uiState.value.copy(rules = appState.rules, isLoading = false)
        }
    }

    fun toggleRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            val rule = appState.rules.firstOrNull { it.id == ruleId } ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            if (enabled) routingEngine.applyRule(rule) else routingEngine.removeRule(rule)
            val updated = rule.copy(enabled = enabled)
            appState = appState.copy(rules = appState.rules.map { if (it.id == ruleId) updated else it })
            stateStore.save(appState)
            _uiState.value = _uiState.value.copy(rules = appState.rules, isLoading = false)
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            routingEngine.resetAll(appState.rules, appState.createdInterfaces)
            appState = AppState()
            stateStore.save(appState)
            refreshInterfaces()
            _uiState.value = _uiState.value.copy(rules = emptyList(), isLoading = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
