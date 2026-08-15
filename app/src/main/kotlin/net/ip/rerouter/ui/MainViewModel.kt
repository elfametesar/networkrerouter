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
import net.ip.rerouter.model.ProxyProtocol
import net.ip.rerouter.model.RouteRule
import net.ip.rerouter.model.SourceInterfaceType
import net.ip.rerouter.model.Tun2socksConfig
import net.ip.rerouter.net.AppRepository
import net.ip.rerouter.net.InterfaceRepository
import net.ip.rerouter.net.RoutingEngine
import net.ip.rerouter.net.StateStore
import net.ip.rerouter.net.Tun2socksEngine
import net.ip.rerouter.root.RootShell
import java.util.UUID

data class UiState(
    val hasRoot: Boolean? = null,
    val interfaces: List<NetInterface> = emptyList(),
    val rules: List<RouteRule> = emptyList(),
    val installedApps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val realRoutingTable: String? = null,
    val policyRules: List<String> = emptyList(),
    val routeTableDump: List<String> = emptyList(),
    val tun2socksSessions: List<Tun2socksConfig> = emptyList(),
    /** Live running-state per session id, refreshed alongside interfaces. */
    val tun2socksRunning: Map<String, Boolean> = emptyMap()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val interfaceRepo = InterfaceRepository()
    private val appRepo = AppRepository(application)
    private val routingEngine = RoutingEngine()
    private val tun2socksEngine = Tun2socksEngine(application)
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
            _uiState.value = _uiState.value.copy(rules = appState.rules, tun2socksSessions = appState.tun2socksSessions)
            refreshRealRoutingTable()
            refreshInterfaces(showLoading = true)
            refreshTun2socksStatus()
            loadApps()

            launch {
                while (isActive) {
                    delay(INTERFACE_REFRESH_MS)
                    refreshInterfaces(showLoading = false)
                    refreshRealRoutingTable()
                    refreshTun2socksStatus()
                }
            }
        }
    }

    private suspend fun refreshRealRoutingTable() {
        val realTable = interfaceRepo.detectRealRoutingTable()
        _uiState.value = _uiState.value.copy(realRoutingTable = realTable)
    }

    fun refreshInterfaces(showLoading: Boolean = false) {
        viewModelScope.launch {
            refreshMutex.withLock {
                if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true)
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
            val apps = appRepo.listInstalledApps(includeSystemApps = true)
            _uiState.value = _uiState.value.copy(installedApps = apps)
        }
    }

    /** Pick a table ID not already assigned in Android's named table registry, policy rules, or app rules. */
    private suspend fun allocateRoutingTable(): Int? {
        val usedByRules = appState.rules.map { it.tableId }.toSet()

        val rtTables = RootShell.exec("cat /etc/iproute2/rt_tables /system/etc/iproute2/rt_tables 2>/dev/null").out
            .mapNotNull { line ->
                val clean = line.substringBefore('#').trim()
                val parts = clean.split(Regex("\\s+"))
                parts.firstOrNull()?.toIntOrNull()
            }
            .toSet()

        val usedByKernel = RootShell.exec("ip rule").out.mapNotNull { line ->
            val lookup = Regex("(?:lookup|table)\\s+([^\\s]+)").find(line)
            lookup?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.toSet()

        val used = usedByRules + rtTables + usedByKernel
        for (table in 100..252) {
            if (table !in used) return table
        }
        return null
    }

    fun createInterface(name: String, isDummy: Boolean, ipCidr: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val ok = if (isDummy) interfaceRepo.createDummyInterface(name, ipCidr)
            else interfaceRepo.createTunInterface(name, ipCidr)
            if (ok) {
                appState = appState.copy(createdInterfaces = appState.createdInterfaces + name)
                stateStore.save(appState)
                refreshInterfaces(false)
                _uiState.value = _uiState.value.copy(isLoading = false)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to create interface $name")
            }
        }
    }

    fun removeInterface(name: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val affected = appState.rules.filter { it.fromInterface == name || it.toInterface == name }
            affected.forEach { routingEngine.removeRule(it, _uiState.value.realRoutingTable) }
            val affectedSessions = appState.tun2socksSessions.filter { it.tunInterface == name }
            affectedSessions.forEach { tun2socksEngine.stop(it.id) }
            val ok = interfaceRepo.removeInterface(name)
            if (ok) {
                appState = appState.copy(
                    createdInterfaces = appState.createdInterfaces - name,
                    rules = appState.rules - affected.toSet(),
                    tun2socksSessions = appState.tun2socksSessions - affectedSessions.toSet()
                )
                stateStore.save(appState)
            } else {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to remove interface $name")
            }
            refreshInterfaces(false)
            refreshRealRoutingTable()
            refreshTun2socksStatus()
            _uiState.value = _uiState.value.copy(rules = appState.rules, tun2socksSessions = appState.tun2socksSessions, isLoading = false)
        }
    }

    fun toggleInterfaceState(name: String, up: Boolean) {
        viewModelScope.launch {
            val ok = interfaceRepo.setInterfaceState(name, up)
            if (!ok) _uiState.value = _uiState.value.copy(errorMessage = "Failed to set $name ${if (up) "up" else "down"}")
            refreshInterfaces(false)
            refreshRealRoutingTable()
        }
    }

    fun setInterfaceMtu(name: String, mtu: Int) {
        viewModelScope.launch {
            val ok = interfaceRepo.setInterfaceMtu(name, mtu)
            if (!ok) _uiState.value = _uiState.value.copy(errorMessage = "Failed to set MTU $mtu on $name")
            refreshInterfaces(false)
        }
    }

    fun addRule(
        fromInterface: String,
        toInterface: String,
        excludedUids: Set<Int>,
        useMasquerade: Boolean,
        proxyAppPackage: String? = null,
        sourceType: SourceInterfaceType = SourceInterfaceType.LOCAL_ONLY
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val liveInterfaces = interfaceRepo.listInterfaces(appState.createdInterfaces.toSet())
            val liveNames = liveInterfaces.map { it.name }.toSet()
            if (fromInterface !in liveNames || toInterface !in liveNames) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Interface disappeared: $fromInterface → $toInterface"
                )
                refreshInterfaces(false)
                return@launch
            }

            // The real network table is dynamic on Android and can change after boot,
            // SIM/network changes, or hotspot transitions. Never rely on the value read only at startup.
            val liveRealTable = interfaceRepo.detectRealRoutingTable()
            _uiState.value = _uiState.value.copy(realRoutingTable = liveRealTable)

            val tableId = allocateRoutingTable()
            if (tableId == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "No free routing table ID is available")
                return@launch
            }

            var effectiveExcluded = excludedUids
            if (proxyAppPackage != null) {
                appRepo.getUidForPackage(proxyAppPackage)?.let { effectiveExcluded = effectiveExcluded + it }
            }

            val rule = RouteRule(
                id = UUID.randomUUID().toString().take(8),
                fromInterface = fromInterface,
                toInterface = toInterface,
                tableId = tableId,
                excludedUids = effectiveExcluded,
                useMasquerade = useMasquerade,
                proxyAppPackage = proxyAppPackage,
                sourceType = sourceType
            )

            val result = routingEngine.applyRule(rule, liveRealTable)
            if (result.isSuccess) {
                appState = appState.copy(
                    rules = appState.rules + rule,
                    nextTableId = if (tableId >= 252) 100 else tableId + 1
                )
                stateStore.save(appState)
                _uiState.value = _uiState.value.copy(rules = appState.rules, isLoading = false)
            } else {
                val reason = result.stderr?.takeIf { it.isNotBlank() } ?: "no error output captured"
                val failedCmd = result.failedCommand?.let { " Command: $it." } ?: ""
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to apply $fromInterface → $toInterface (table $tableId).$failedCmd Error: $reason"
                )
            }
        }
    }

    fun removeRule(ruleId: String) {
        viewModelScope.launch {
            val rule = appState.rules.firstOrNull { it.id == ruleId } ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            val ok = routingEngine.removeRule(rule, _uiState.value.realRoutingTable)
            if (ok) {
                appState = appState.copy(rules = appState.rules - rule)
                stateStore.save(appState)
                _uiState.value = _uiState.value.copy(rules = appState.rules, isLoading = false)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to remove route ${rule.fromInterface} → ${rule.toInterface}")
            }
        }
    }

    fun toggleRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            val rule = appState.rules.firstOrNull { it.id == ruleId } ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            val liveRealTable = interfaceRepo.detectRealRoutingTable()
            _uiState.value = _uiState.value.copy(realRoutingTable = liveRealTable)
            val ok = if (enabled) routingEngine.applyRule(rule, liveRealTable).isSuccess
            else routingEngine.removeRule(rule, liveRealTable)
            if (!ok) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to ${if (enabled) "enable" else "disable"} route")
                return@launch
            }
            val updated = rule.copy(enabled = enabled)
            appState = appState.copy(rules = appState.rules.map { if (it.id == ruleId) updated else it })
            stateStore.save(appState)
            _uiState.value = _uiState.value.copy(rules = appState.rules, isLoading = false)
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            appState.tun2socksSessions.forEach { tun2socksEngine.stop(it.id) }
            routingEngine.resetAll(appState.rules, appState.createdInterfaces, _uiState.value.realRoutingTable)
            appState = AppState()
            stateStore.save(appState)
            refreshInterfaces(false)
            refreshRealRoutingTable()
            _uiState.value = _uiState.value.copy(rules = emptyList(), tun2socksSessions = emptyList(), tun2socksRunning = emptyMap(), isLoading = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** Loads live `ip rule` and `ip route show table all` for the diagnostics view, so failures are inspectable instead of guessed at. */
    fun refreshDiagnostics() {
        viewModelScope.launch {
            val rules = interfaceRepo.listPolicyRules()
            val routes = interfaceRepo.listRoutes()
            _uiState.value = _uiState.value.copy(policyRules = rules, routeTableDump = routes)
        }
    }

    private suspend fun refreshTun2socksStatus() {
        val running = appState.tun2socksSessions.associate { it.id to tun2socksEngine.isRunning(it.id) }
        _uiState.value = _uiState.value.copy(tun2socksRunning = running)
    }

    /**
     * Creates (or updates, if a session for this tunInterface already exists)
     * a tun2socks session and starts it immediately. `tunInterface` must
     * already exist (create it first via createInterface). The real egress
     * interface tun2socks dials the proxy through is detected live, same as
     * every other real-routing-table lookup in this app — never hardcoded.
     */
    fun startTun2socks(
        tunInterface: String,
        proxyProtocol: ProxyProtocol,
        proxyAddress: String,
        proxyUsername: String?,
        proxyPassword: String?,
        mtu: Int,
        apiPort: Int?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val existing = appState.tun2socksSessions.firstOrNull { it.tunInterface == tunInterface }
            val config = Tun2socksConfig(
                id = existing?.id ?: UUID.randomUUID().toString().take(8),
                tunInterface = tunInterface,
                proxyProtocol = proxyProtocol,
                proxyAddress = proxyAddress,
                proxyUsername = proxyUsername?.takeIf { it.isNotBlank() },
                proxyPassword = proxyPassword?.takeIf { it.isNotBlank() },
                mtu = mtu,
                apiPort = apiPort,
                enabled = true
            )

            val liveRealTable = interfaceRepo.detectRealRoutingTable()
            val result = tun2socksEngine.start(config, realInterface = liveRealTable)
            if (!result.isSuccess) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error ?: "Failed to start tun2socks")
                return@launch
            }

            appState = appState.copy(
                tun2socksSessions = appState.tun2socksSessions.filterNot { it.id == config.id } + config
            )
            stateStore.save(appState)
            refreshTun2socksStatus()
            _uiState.value = _uiState.value.copy(tun2socksSessions = appState.tun2socksSessions, isLoading = false)
        }
    }

    fun stopTun2socks(sessionId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            tun2socksEngine.stop(sessionId)
            refreshTun2socksStatus()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun removeTun2socksSession(sessionId: String) {
        viewModelScope.launch {
            tun2socksEngine.stop(sessionId)
            appState = appState.copy(tun2socksSessions = appState.tun2socksSessions.filterNot { it.id == sessionId })
            stateStore.save(appState)
            refreshTun2socksStatus()
            _uiState.value = _uiState.value.copy(tun2socksSessions = appState.tun2socksSessions)
        }
    }

    /** Fetches the tail of a session's stdout/stderr log for troubleshooting. */
    suspend fun tun2socksLog(sessionId: String): List<String> = tun2socksEngine.recentLog(sessionId)

    private companion object {
        const val INTERFACE_REFRESH_MS = 2000L
    }
}
