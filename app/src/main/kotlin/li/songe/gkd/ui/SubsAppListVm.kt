package li.songe.gkd.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ramcosta.composedestinations.generated.destinations.SubsAppListPageDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import li.songe.gkd.data.AppConfig
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.db.DbSet
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LinkLoad
import li.songe.gkd.util.SortTypeOption
import li.songe.gkd.util.ViewModelExt
import li.songe.gkd.util.appInfoCacheFlow
import li.songe.gkd.util.collator
import li.songe.gkd.util.findOption
import li.songe.gkd.util.getGroupEnable
import li.songe.gkd.util.map
import li.songe.gkd.util.subsIdToRawFlow

class SubsAppListVm(stateHandle: SavedStateHandle) : ViewModelExt() {
    private val args = SubsAppListPageDestination.argsFrom(stateHandle)
    val linkLoad = LinkLoad(viewModelScope)
    val subsRawFlow = subsIdToRawFlow.map(viewModelScope) { s -> s[args.subsItemId] }

    private val appConfigsFlow = DbSet.appConfigDao.queryAppTypeConfig(args.subsItemId)
        .let(linkLoad::invoke).stateInit(emptyList())

    private val groupSubsConfigsFlow = DbSet.subsConfigDao.querySubsGroupTypeConfig(args.subsItemId)
        .let(linkLoad::invoke).stateInit(emptyList())

    private val categoryConfigsFlow = DbSet.categoryConfigDao.queryConfig(args.subsItemId)
        .let(linkLoad::invoke).stateInit(emptyList())

    private val appIdToOrderFlow =
        DbSet.actionLogDao.queryLatestUniqueAppIds(args.subsItemId).let(linkLoad::invoke)
            .map { appIds ->
                appIds.mapIndexed { index, appId -> appId to index }.toMap()
            }
    val sortTypeFlow =
        storeFlow.map(viewModelScope) { SortTypeOption.allSubObject.findOption(it.subsAppSortType) }

    val showUninstallAppFlow = storeFlow.map(viewModelScope) { it.subsAppShowUninstallApp }
    private val rawAppsFlow = subsRawFlow.map(viewModelScope) {
        (it?.apps ?: emptyList()).run {
            if (any { it.groups.isEmpty() }) {
                filterNot { it.groups.isEmpty() }
            } else {
                this
            }
        }
    }
    private val temp0ListFlow = combine(rawAppsFlow, appInfoCacheFlow) { rawApps, appInfoCache ->
        rawApps.sortedWith { a, b ->
            // 顺序: 已安装(有名字->无名字)->未安装(有名字(来自订阅)->无名字)
            collator.compare(appInfoCache[a.id]?.name ?: a.name?.let { "\uFFFF" + it }
            ?: ("\uFFFF\uFFFF" + a.id),
                appInfoCache[b.id]?.name ?: b.name?.let { "\uFFFF" + it }
                ?: ("\uFFFF\uFFFF" + b.id))
        }
    }
    private val temp1ListFlow = combine(
        temp0ListFlow,
        appInfoCacheFlow,
        showUninstallAppFlow
    ) { apps, appInfoCache, showUninstallApp ->
        if (showUninstallApp) {
            apps
        } else {
            apps.filter { a -> appInfoCache.containsKey(a.id) }
        }
    }
    private val sortAppsFlow = combine(
        temp1ListFlow,
        appInfoCacheFlow,
        appIdToOrderFlow,
        sortTypeFlow
    ) { apps, appInfoCache, appIdToOrder, sortType ->
        when (sortType) {
            SortTypeOption.SortByAppMtime -> {
                apps.sortedBy { a -> -(appInfoCache[a.id]?.mtime ?: 0) }
            }

            SortTypeOption.SortByTriggerTime -> {
                apps.sortedBy { a -> appIdToOrder[a.id] ?: Int.MAX_VALUE }
            }

            SortTypeOption.SortByName -> {
                apps
            }
        }
    }.stateInit(emptyList())

    val searchStrFlow = MutableStateFlow("")

    private val debounceSearchStr = searchStrFlow.debounce(200).stateInit(searchStrFlow.value)


    private val appAndConfigsFlow = combine(
        subsRawFlow,
        sortAppsFlow,
        categoryConfigsFlow,
        appConfigsFlow,
        groupSubsConfigsFlow,
    ) { subsRaw, apps, categoryConfigs, appSubsConfigs, groupSubsConfigs ->
        val groupToCategoryMap = subsRaw?.groupToCategoryMap ?: emptyMap()
        apps.map { app ->
            val appGroupSubsConfigs = groupSubsConfigs.filter { s -> s.appId == app.id }
            val enableSize = app.groups.count { g ->
                getGroupEnable(
                    g,
                    appGroupSubsConfigs.find { c -> c.groupKey == g.key },
                    groupToCategoryMap[g],
                    categoryConfigs.find { c -> c.categoryKey == groupToCategoryMap[g]?.key }
                )
            }
            Triple(app, appSubsConfigs.find { s -> s.appId == app.id }, enableSize)
        }
    }.stateInit(emptyList())

    val filterAppAndConfigsFlow = combine(
        appAndConfigsFlow, debounceSearchStr, appInfoCacheFlow
    ) { appAndConfigs, searchStr, appInfoCache ->
        if (searchStr.isBlank()) {
            appAndConfigs
        } else {
            val results = mutableListOf<Triple<RawSubscription.RawApp, AppConfig?, Int>>()
            val remnantList = appAndConfigs.toMutableList()
            //1. 搜索已安装应用名称
            remnantList.toList().apply { remnantList.clear() }.forEach { a ->
                val name = appInfoCache[a.first.id]?.name
                if (name?.contains(searchStr, true) == true) {
                    results.add(a)
                } else {
                    remnantList.add(a)
                }
            }
            //2. 搜索未安装应用名称
            remnantList.toList().apply { remnantList.clear() }.forEach { a ->
                val name = a.first.name
                if (appInfoCache[a.first.id] == null && name?.contains(searchStr, true) == true) {
                    results.add(a)
                } else {
                    remnantList.add(a)
                }
            }
            //3. 搜索应用 id
            remnantList.toList().apply { remnantList.clear() }.forEach { a ->
                if (a.first.id.contains(searchStr, true)) {
                    results.add(a)
                } else {
                    remnantList.add(a)
                }
            }
            results
        }
    }.stateInit(emptyList())

}