package com.lifehub.app.plugins

import com.lifehub.app.plugin.runtime.PluginModule
import com.lifehub.app.plugin.runtime.dataSourceKey
import com.lifehub.app.plugin.runtime.readAllRecords

import com.lifehub.pluginapi.PluginAction
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionHandler
import com.lifehub.pluginapi.PluginActionResult
import com.lifehub.pluginapi.PluginDataAction
import com.lifehub.pluginapi.PluginDataItem
import com.lifehub.pluginapi.PluginDataLimits
import com.lifehub.pluginapi.PluginDataResult
import com.lifehub.pluginapi.PluginDataTone
import com.lifehub.pluginapi.PluginMetric
import com.lifehub.pluginapi.PluginBar
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataHandler
import com.lifehub.pluginapi.PluginSegment
import com.lifehub.pluginapi.PluginStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

@Serializable
internal data class LedgerEntry(
    val id: String,
    val amountMinor: Long,
    val currency: String = "CNY",
    val direction: String,
    val category: String,
    val occurredOn: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** 借贷标记：lend 我借出（应收），borrow 我借入（应付）；空表示普通记录。 */
    val loanKind: String = "",
    /** 借贷对方。 */
    val counterparty: String = "",
    /** 还款记录关联的借款记录 id。 */
    val linkId: String = "",
)

@Serializable
internal data class LedgerCategoryRegistry(
    /** 分类名到 Emoji 的映射，覆盖默认分类或保存自定义分类。 */
    val emojiByCategory: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0,
)

@Serializable
internal data class LedgerTrashEntry(
    val entry: LedgerEntry,
    val deletedAt: Long,
)

@Serializable
internal data class LedgerBudget(
    val amountMinor: Long,
    val updatedAt: Long,
    /** 开启后，上月未用完的预算计入本月可用额度。保留用于旧数据兼容。 */
    val carryOver: Boolean = false,
    /** 结转模式：off / positive（仅结余）/ both（结余与超支）。 */
    val carryMode: String = "",
    /** 分类名到月度预算金额的映射。 */
    val categoryBudgets: Map<String, Long> = emptyMap(),
    /** 按 "YYYY-MM" 保存的每月预算；0 表示该月已清除。旧数据的 amountMinor 作为默认值。 */
    val monthlyBudgets: Map<String, Long> = emptyMap(),
)

object LedgerPlugin : PluginModule {
    const val PLUGIN_ID: String = "com.lifehub.plugin.ledger"

    override val pluginId: String = PLUGIN_ID
    const val SOURCE_ENTRIES: String = "ledger_entries"
    const val SOURCE_SUMMARY: String = "ledger_summary"
    const val SOURCE_INSIGHTS: String = "ledger_insights"
    const val SOURCE_BUDGET: String = "ledger_budget"
    const val SOURCE_SEARCH: String = "ledger_search"
    const val SOURCE_CATEGORIES: String = "ledger_categories"
    const val SOURCE_LOANS: String = "ledger_loans"
    const val SOURCE_LOAN_OPTIONS: String = "ledger_loan_options"

    const val LOAN_CATEGORY: String = "借钱"
    const val REPAY_CATEGORY: String = "还钱"
    const val LOAN_LEND: String = "lend"
    const val LOAN_BORROW: String = "borrow"

    private const val COLLECTION = "entries"
    private const val TRASH_COLLECTION = "entries_trash"
    private const val BUDGET_COLLECTION = "budget"
    private const val BUDGET_ID = "monthly"
    private const val CATEGORY_COLLECTION = "categories"
    private const val CATEGORY_ID = "registry"
    private const val PAGE_LIST = "list"
    private const val PAGE_EDIT = "edit"
    private const val PAGE_STATS = "stats"
    private const val PAGE_BUDGET = "budget"
    private const val PAGE_BUDGET_CATEGORY = "budget-category"
    private const val PAGE_SEARCH = "search"
    private const val PAGE_LOANS = "loans"
    private const val PAGE_CATEGORY_PICK = "category-pick"
    private const val PAGE_CATEGORY_NEW = "category-new"
    private const val PAGE_LOAN_MATCH = "loan-match"
    private const val TRASH_RETENTION_DAYS = 30L
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val MAX_CATEGORY_BUDGETS = 30
    private const val MAX_CATEGORY_OPTIONS = 40
    private const val MAX_BUDGET_FORWARD_MONTHS = 36
    private const val CARRY_OFF = "off"
    private const val CARRY_POSITIVE = "positive"
    private const val CARRY_BOTH = "both"
    private val CARRY_MODES = setOf(CARRY_OFF, CARRY_POSITIVE, CARRY_BOTH)

    const val ACTION_SAVE: String = "$PLUGIN_ID.save"
    const val ACTION_SEARCH: String = "$PLUGIN_ID.search"
    const val ACTION_FILTER: String = "$PLUGIN_ID.filter"
    const val ACTION_SET_BUDGET: String = "$PLUGIN_ID.set_budget"
    const val ACTION_CLEAR_BUDGET: String = "$PLUGIN_ID.clear_budget"
    const val ACTION_SET_BUDGET_CARRY: String = "$PLUGIN_ID.set_budget_carry"
    const val ACTION_APPLY_BUDGET_FORWARD: String = "$PLUGIN_ID.apply_budget_forward"
    const val ACTION_SET_CATEGORY_BUDGET: String = "$PLUGIN_ID.set_category_budget"
    const val ACTION_CLEAR_CATEGORY_BUDGET: String = "$PLUGIN_ID.clear_category_budget"
    const val ACTION_DELETE: String = "$PLUGIN_ID.delete"
    const val ACTION_RESTORE: String = "$PLUGIN_ID.restore"
    const val ACTION_PERIOD: String = "$PLUGIN_ID.period"
    const val ACTION_SHIFT: String = "$PLUGIN_ID.shift"
    const val ACTION_DRILLDOWN: String = "$PLUGIN_ID.drilldown"
    const val ACTION_LINK_REPAYMENT: String = "$PLUGIN_ID.link_repayment"
    const val ACTION_CREATE_CATEGORY: String = "$PLUGIN_ID.create_category"

    /** 常用分类的默认 Emoji；自定义分类可以覆盖。 */
    private val DEFAULT_CATEGORY_EMOJIS: Map<String, String> = linkedMapOf(
        "餐饮" to "🍜",
        "交通" to "🚕",
        "购物" to "🛍️",
        "居住" to "🏠",
        "娱乐" to "🎮",
        "医疗" to "💊",
        "工资" to "💰",
        LOAN_CATEGORY to "🤝",
        REPAY_CATEGORY to "🔁",
        "其他" to "📦",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val actionHandlers: Map<String, PluginActionHandler> = mapOf(
        ACTION_SAVE to PluginActionHandler { context -> save(context) },
        ACTION_SEARCH to PluginActionHandler { context -> search(context) },
        ACTION_FILTER to PluginActionHandler { context -> setDirection(context) },
        ACTION_SET_BUDGET to PluginActionHandler { context -> setBudget(context) },
        ACTION_CLEAR_BUDGET to PluginActionHandler { context -> clearBudget(context) },
        ACTION_SET_BUDGET_CARRY to PluginActionHandler { context -> setBudgetCarry(context) },
        ACTION_APPLY_BUDGET_FORWARD to PluginActionHandler { context ->
            applyBudgetForward(context)
        },
        ACTION_SET_CATEGORY_BUDGET to PluginActionHandler { context -> setCategoryBudget(context) },
        ACTION_CLEAR_CATEGORY_BUDGET to PluginActionHandler { context -> clearCategoryBudget(context) },
        ACTION_DELETE to PluginActionHandler { context -> delete(context) },
        ACTION_RESTORE to PluginActionHandler { context -> restore(context) },
        ACTION_PERIOD to PluginActionHandler { context -> setPeriod(context) },
        ACTION_SHIFT to PluginActionHandler { context -> shiftPeriod(context) },
        ACTION_DRILLDOWN to PluginActionHandler { context -> drilldown(context) },
        ACTION_LINK_REPAYMENT to PluginActionHandler { context -> linkRepayment(context) },
        ACTION_CREATE_CATEGORY to PluginActionHandler { context -> createCategory(context) },
    )

    override val dataHandlers: Map<String, PluginPageDataHandler> = mapOf(
        dataSourceKey(PLUGIN_ID, SOURCE_ENTRIES) to PluginPageDataHandler { context ->
            loadEntries(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_SUMMARY) to PluginPageDataHandler { context ->
            loadSummary(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_INSIGHTS) to PluginPageDataHandler { context ->
            loadInsights(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_BUDGET) to PluginPageDataHandler { context ->
            loadBudget(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_SEARCH) to PluginPageDataHandler { context ->
            searchAll(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_CATEGORIES) to PluginPageDataHandler { context ->
            loadCategories(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_LOANS) to PluginPageDataHandler { context ->
            loadLoans(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_LOAN_OPTIONS) to PluginPageDataHandler { context ->
            loadLoanOptions(context)
        },
    )

    private fun save(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val params = context.request.params

        val amountMinor = runCatching { parseAmount(params["amount"]) }
            .getOrElse { return PluginActionResult.Failure(it.message ?: "金额不合法") }
        var direction = runCatching { parseDirection(params["direction"]) }
            .getOrElse { return PluginActionResult.Failure(it.message ?: "收支方向不合法") }
        val category = params["category"]?.trim().orEmpty()
        if (category.isBlank()) return PluginActionResult.Failure("分类不能为空")
        if (category.length > 40) return PluginActionResult.Failure("分类最多 40 个字")
        val categoryEmoji = params["categoryEmoji"]?.trim().orEmpty()
        if (categoryEmoji.length > 8) return PluginActionResult.Failure("分类图标不合法")
        val occurredOn = runCatching { parseDate(params["date"]) }
            .getOrElse { return PluginActionResult.Failure(it.message ?: "日期不合法") }
        val note = params["note"]?.trim().orEmpty()
        if (note.length > 500) return PluginActionResult.Failure("备注最多 500 个字")

        var loanKind = normalizeLoanKind(params["loanKind"])
        var counterparty = params["counterparty"]?.trim().orEmpty()
        if (counterparty.length > 40) return PluginActionResult.Failure("借贷对方最多 40 个字")
        var linkId = params["linkId"]?.trim().orEmpty()
        if (category == LOAN_CATEGORY) {
            if (loanKind.isBlank()) {
                return PluginActionResult.Failure("请选择借贷方向：我借出或我借入")
            }
            if (counterparty.isBlank()) return PluginActionResult.Failure("请填写借贷对方")
            direction = if (loanKind == LOAN_LEND) "expense" else "income"
            linkId = ""
        } else if (linkId.isNotBlank()) {
            val loan = storage.get(COLLECTION, linkId)?.let { record ->
                runCatching { json.decodeFromString<LedgerEntry>(record.payload) }.getOrNull()
            }
            if (loan == null || loan.category != LOAN_CATEGORY || loan.loanKind.isBlank()) {
                return PluginActionResult.Failure("关联的借款不存在")
            }
            loanKind = loan.loanKind
            counterparty = loan.counterparty
            direction = if (loanKind == LOAN_LEND) "income" else "expense"
        } else {
            loanKind = ""
            counterparty = ""
        }

        val now = System.currentTimeMillis()
        val id = params["id"]?.trim().orEmpty()
        return try {
            if (id.isBlank()) {
                val entry = LedgerEntry(
                    id = UUID.randomUUID().toString(),
                    amountMinor = amountMinor,
                    direction = direction,
                    category = category,
                    occurredOn = occurredOn,
                    note = note,
                    createdAt = now,
                    updatedAt = now,
                    loanKind = loanKind,
                    counterparty = counterparty,
                    linkId = linkId,
                )
                storage.insert(COLLECTION, json.encodeToString(entry), entry.id)
            } else {
                val existing = storage.get(COLLECTION, id)
                    ?: return PluginActionResult.Failure("记录不存在")
                val previous = runCatching {
                    json.decodeFromString<LedgerEntry>(existing.payload)
                }.getOrNull()
                val entry = LedgerEntry(
                    id = id,
                    amountMinor = amountMinor,
                    currency = previous?.currency ?: "CNY",
                    direction = direction,
                    category = category,
                    occurredOn = occurredOn,
                    note = note,
                    createdAt = previous?.createdAt ?: now,
                    updatedAt = now,
                    loanKind = loanKind,
                    counterparty = counterparty,
                    linkId = linkId,
                )
                if (!storage.update(COLLECTION, id, json.encodeToString(entry))) {
                    return PluginActionResult.Failure("记录不存在")
                }
            }
            runCatching { rememberCategory(storage, category, categoryEmoji) }
            val viewParams = params.filterKeys { it.startsWith("return") }
            val listViewParams = buildMap {
                viewParams["returnPeriod"]?.let { put("period", it) }
                viewParams["returnAnchor"]?.let { put("anchor", it) }
                viewParams["returnQuery"]?.let { put("query", it) }
                viewParams["returnDirection"]?.let { put("direction", it) }
            }
            if (params["mode"] == "continue") {
                PluginActionResult.Navigate(
                    PAGE_EDIT,
                    mapOf(
                        "direction" to if (direction == "income") "收入" else "支出",
                        "category" to category,
                        "date" to LocalDate.now().toString(),
                    ) + viewParams,
                )
            } else {
                when (params["returnTo"]?.trim().orEmpty()) {
                    "loans" -> PluginActionResult.Navigate(PAGE_LOANS)
                    "search" -> PluginActionResult.Navigate(
                        PAGE_SEARCH,
                        params["returnQuery"]?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { mapOf("query" to it) }
                            ?: emptyMap(),
                    )

                    else -> PluginActionResult.Navigate(PAGE_LIST, listParams(listViewParams))
                }
            }
        } catch (error: Exception) {
            PluginActionResult.Failure("保存失败：${error.message ?: "未知错误"}")
        }
    }

    private fun search(context: PluginActionContext): PluginActionResult {
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) return PluginActionResult.ShowMessage("请输入搜索关键词")
        return PluginActionResult.Navigate(PAGE_SEARCH, mapOf("query" to query))
    }

    private fun setDirection(context: PluginActionContext): PluginActionResult =
        PluginActionResult.Navigate(PAGE_LIST, listParams(context.request.params))

    private fun setBudget(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val raw = context.request.params["amount"]?.trim().orEmpty()
        val clear = runCatching { BigDecimal(raw).signum() == 0 }.getOrDefault(false)
        if (clear) {
            return clearBudgetForMonth(
                storage,
                YearMonth.from(parseAnchor(context.request.params["anchor"])),
            )
        }
        val amount = runCatching { parseAmount(raw) }
            .getOrElse { return PluginActionResult.Failure(it.message ?: "预算金额不合法") }
        // 保存月度预算时保留结转开关与分类预算，避免互相覆盖。
        val existing = readBudgetRecord(storage) ?: LedgerBudget(
            amountMinor = 0L,
            updatedAt = System.currentTimeMillis(),
        )
        val month = YearMonth.from(parseAnchor(context.request.params["anchor"]))
        val budget = existing.copy(
            monthlyBudgets = existing.monthlyBudgets + (month.toString() to amount),
            updatedAt = System.currentTimeMillis(),
        )
        return runCatching {
            writeBudget(storage, budget)
            PluginActionResult.Refresh(
                message = "预算已保存",
                clearInputIds = listOf("amount"),
            )
        }.getOrElse { error ->
            PluginActionResult.Failure("预算保存失败：${error.message ?: "未知错误"}")
        }
    }

    private fun clearBudget(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val month = YearMonth.from(parseAnchor(context.request.params["anchor"]))
        return clearBudgetForMonth(storage, month)
    }

    /** 只清除指定月份，保留结转开关、分类预算与其他月份。 */
    private fun clearBudgetForMonth(
        storage: PluginStorage,
        month: YearMonth,
    ): PluginActionResult {
        val existing = readBudgetRecord(storage)
            ?: return PluginActionResult.Refresh(
                message = "预算已清除",
                clearInputIds = listOf("amount"),
            )
        return runCatching {
            writeBudget(
                storage,
                existing.copy(
                    monthlyBudgets = existing.monthlyBudgets + (month.toString() to 0L),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            PluginActionResult.Refresh(
                message = "预算已清除",
                clearInputIds = listOf("amount"),
            )
        }.getOrElse { error ->
            PluginActionResult.Failure("预算清除失败：${error.message ?: "未知错误"}")
        }
    }

    private fun setBudgetCarry(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val rawMode = context.request.params["mode"]?.trim()?.lowercase(Locale.ROOT)
        val legacyEnabled = context.request.params["carry"]?.trim()?.lowercase(Locale.ROOT)
            ?.let { it in setOf("on", "true", "1") }
        val mode = when {
            rawMode != null && rawMode in CARRY_MODES -> rawMode
            legacyEnabled != null -> if (legacyEnabled) CARRY_POSITIVE else CARRY_OFF
            else -> CARRY_OFF
        }
        val now = System.currentTimeMillis()
        val existing = readBudgetRecord(storage) ?: LedgerBudget(amountMinor = 0L, updatedAt = now)
        return runCatching {
            writeBudget(
                storage,
                existing.copy(
                    carryOver = mode != CARRY_OFF,
                    carryMode = mode,
                    updatedAt = now,
                ),
            )
            PluginActionResult.Refresh(
                message = when (mode) {
                    CARRY_OFF -> "已关闭预算结转"
                    CARRY_POSITIVE -> "已开启结余结转"
                    else -> "已开启双向结转"
                },
            )
        }.getOrElse { error ->
            PluginActionResult.Failure("预算结转设置失败：${error.message ?: "未知错误"}")
        }
    }

    /** 把金额写入从当前月开始的连续月份，作为轻量预算模板。 */
    private fun applyBudgetForward(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val params = context.request.params
        val amount = runCatching { parseAmount(params["amount"]) }
            .getOrElse { return PluginActionResult.Failure(it.message ?: "预算金额不合法") }
        val months = params["months"]?.trim()?.toIntOrNull()
            ?: return PluginActionResult.Failure("请输入连续月数")
        if (months !in 1..MAX_BUDGET_FORWARD_MONTHS) {
            return PluginActionResult.Failure(
                "连续月数需在 1 到 $MAX_BUDGET_FORWARD_MONTHS 之间",
            )
        }
        val start = YearMonth.from(parseAnchor(params["anchor"]))
        val now = System.currentTimeMillis()
        val existing = readBudgetRecord(storage) ?: LedgerBudget(amountMinor = 0L, updatedAt = now)
        val updated = existing.monthlyBudgets.toMutableMap()
        repeat(months) { offset ->
            updated[start.plusMonths(offset.toLong()).toString()] = amount
        }
        return runCatching {
            writeBudget(
                storage,
                existing.copy(monthlyBudgets = updated, updatedAt = now),
            )
            PluginActionResult.Refresh(
                message = "已应用到 $months 个月",
                clearInputIds = listOf("amount", "months"),
            )
        }.getOrElse { error ->
            PluginActionResult.Failure("预算应用失败：${error.message ?: "未知错误"}")
        }
    }

    private fun setCategoryBudget(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val category = context.request.params["category"]?.trim().orEmpty()
        if (category.isBlank()) return PluginActionResult.Failure("分类不能为空")
        if (category.length > 40) return PluginActionResult.Failure("分类最多 40 个字")
        val raw = context.request.params["amount"]?.trim().orEmpty()
        if (runCatching { BigDecimal(raw).signum() == 0 }.getOrDefault(false)) {
            return clearCategoryBudget(storage, category)
        }
        val amount = runCatching { parseAmount(raw) }
            .getOrElse { return PluginActionResult.Failure(it.message ?: "预算金额不合法") }
        val now = System.currentTimeMillis()
        val existing = readBudgetRecord(storage) ?: LedgerBudget(amountMinor = 0L, updatedAt = now)
        if (existing.categoryBudgets.size >= MAX_CATEGORY_BUDGETS &&
            category !in existing.categoryBudgets
        ) {
            return PluginActionResult.Failure("最多设置 $MAX_CATEGORY_BUDGETS 个分类预算")
        }
        return runCatching {
            writeBudget(
                storage,
                existing.copy(
                    categoryBudgets = existing.categoryBudgets + (category to amount),
                    updatedAt = now,
                ),
            )
            PluginActionResult.Navigate(PAGE_BUDGET)
        }.getOrElse { error ->
            PluginActionResult.Failure("分类预算保存失败：${error.message ?: "未知错误"}")
        }
    }

    private fun clearCategoryBudget(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val category = context.request.params["category"]?.trim().orEmpty()
        if (category.isBlank()) return PluginActionResult.Failure("分类不能为空")
        return clearCategoryBudget(storage, category)
    }

    private fun clearCategoryBudget(
        storage: PluginStorage,
        category: String,
    ): PluginActionResult {
        val existing = readBudgetRecord(storage)
        if (existing == null || category !in existing.categoryBudgets) {
            return PluginActionResult.Navigate(PAGE_BUDGET)
        }
        return runCatching {
            writeBudget(
                storage,
                existing.copy(
                    categoryBudgets = existing.categoryBudgets - category,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            PluginActionResult.Navigate(PAGE_BUDGET)
        }.getOrElse { error ->
            PluginActionResult.Failure("分类预算移除失败：${error.message ?: "未知错误"}")
        }
    }

    private fun delete(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少记录 ID")
        val record = storage.get(COLLECTION, id)
            ?: return PluginActionResult.Failure("记录不存在")
        val entry = runCatching { json.decodeFromString<LedgerEntry>(record.payload) }.getOrNull()
            ?: return PluginActionResult.Failure("记录数据损坏")
        val trash = LedgerTrashEntry(entry = entry, deletedAt = System.currentTimeMillis())
        val payload = json.encodeToString(trash)
        if (storage.get(TRASH_COLLECTION, id) == null) {
            storage.insert(TRASH_COLLECTION, payload, id)
        } else {
            storage.update(TRASH_COLLECTION, id, payload)
        }
        return if (storage.delete(COLLECTION, id)) {
            PluginActionResult.Refresh(
                message = "已删除记录",
                undoLabel = "撤销",
                undoAction = PluginAction.Host(ACTION_RESTORE, mapOf("id" to id)),
            )
        } else {
            PluginActionResult.Failure("记录不存在")
        }
    }

    private fun restore(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少记录 ID")
        val record = storage.get(TRASH_COLLECTION, id)
            ?: return PluginActionResult.Failure("记录不在回收站")
        val trash = runCatching {
            json.decodeFromString<LedgerTrashEntry>(record.payload)
        }.getOrNull() ?: return PluginActionResult.Failure("记录数据损坏")
        val payload = json.encodeToString(trash.entry)
        if (storage.get(COLLECTION, id) == null) {
            storage.insert(COLLECTION, payload, id)
        } else {
            storage.update(COLLECTION, id, payload)
        }
        storage.delete(TRASH_COLLECTION, id)
        return PluginActionResult.Refresh("已恢复记录")
    }

    private fun purgeExpiredTrash(storage: PluginStorage, now: Long) {
        val cutoff = now - TRASH_RETENTION_DAYS * DAY_MILLIS
        storage.readAllRecords(TRASH_COLLECTION).forEach { record ->
            val entry = runCatching {
                json.decodeFromString<LedgerTrashEntry>(record.payload)
            }.getOrNull()
            if (entry == null || entry.deletedAt <= cutoff) {
                storage.delete(TRASH_COLLECTION, record.id)
            }
        }
    }

    private fun setPeriod(context: PluginActionContext): PluginActionResult {
        val mode = parsePeriodMode(context.request.params["period"])
        val anchor = parseAnchor(context.request.params["anchor"])
        return PluginActionResult.Navigate(
            periodPage(context),
            listParams(context.request.params) +
                mapOf("period" to mode, "anchor" to anchor.toString()),
        )
    }

    private fun shiftPeriod(context: PluginActionContext): PluginActionResult {
        val mode = parsePeriodMode(context.request.params["period"])
        val anchor = parseAnchor(context.request.params["anchor"])
        val delta = context.request.params["delta"]
            ?.toIntOrNull()
            ?.coerceIn(-1_000, 1_000)
            ?: 0
        val shifted = when (mode) {
            "day" -> anchor.plusDays(delta.toLong())
            "month" -> anchor.plusMonths(delta.toLong())
            else -> anchor.plusYears(delta.toLong())
        }
        return PluginActionResult.Navigate(
            periodPage(context),
            listParams(context.request.params) +
                mapOf("period" to mode, "anchor" to shifted.toString()),
        )
    }

    /** 统计页点选柱子后进入对应明细：月看当日、年看当月、日看该分类。 */
    private fun drilldown(context: PluginActionContext): PluginActionResult {
        val params = context.request.params
        val mode = parsePeriodMode(params["period"])
        val anchor = parseAnchor(params["anchor"])
        val label = params["barLabel"]?.trim().orEmpty()
        if (label.isBlank()) return PluginActionResult.Success
        return when (mode) {
            "month" -> {
                val day = label.removeSuffix("日").toIntOrNull()
                    ?: return PluginActionResult.Success
                val date = runCatching { YearMonth.from(anchor).atDay(day) }.getOrNull()
                    ?: return PluginActionResult.Success
                PluginActionResult.Navigate(
                    PAGE_LIST,
                    mapOf("period" to "day", "anchor" to date.toString()),
                )
            }

            "year" -> {
                val month = label.removeSuffix("月").toIntOrNull()
                    ?: return PluginActionResult.Success
                val date = runCatching { LocalDate.of(anchor.year, month, 1) }.getOrNull()
                    ?: return PluginActionResult.Success
                PluginActionResult.Navigate(
                    PAGE_LIST,
                    mapOf("period" to "month", "anchor" to date.toString()),
                )
            }

            else -> PluginActionResult.Navigate(
                PAGE_LIST,
                mapOf(
                    "period" to "day",
                    "anchor" to anchor.toString(),
                    "query" to label,
                ),
            )
        }
    }

    /** 统计页与预算页切换周期后停留在当前页，账本页保持在账本页。 */
    private fun periodPage(context: PluginActionContext): String =
        when (context.request.pageId) {
            PAGE_STATS -> PAGE_STATS
            PAGE_BUDGET -> PAGE_BUDGET
            else -> PAGE_LIST
        }

    private fun loadSummary(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val mode = parsePeriodMode(context.request.params["period"])
        val anchor = parseAnchor(context.request.params["anchor"])
        val entries = storage.readAllRecords(COLLECTION)
            .mapNotNull { record ->
                runCatching { json.decodeFromString<LedgerEntry>(record.payload) }.getOrNull()
            }
            .filter { entry ->
                val date = runCatching { LocalDate.parse(entry.occurredOn) }.getOrNull()
                    ?: return@filter false
                when (mode) {
                    "day" -> date == anchor
                    "month" -> YearMonth.from(date) == YearMonth.from(anchor)
                    else -> date.year == anchor.year
                }
            }
        val expenseMinor = entries.filter { it.direction == "expense" }.sumOf { it.amountMinor }
        val incomeMinor = entries.filter { it.direction == "income" }.sumOf { it.amountMinor }
        val expenseLabel = when (mode) {
            "day" -> "当日支出"
            "month" -> "本月支出"
            else -> "本年支出"
        }
        val incomeLabel = when (mode) {
            "day" -> "当日收入"
            "month" -> "本月收入"
            else -> "本年收入"
        }
        return PluginDataResult(
            metrics = listOf(
                PluginMetric(
                    label = expenseLabel,
                    value = formatMinor(expenseMinor),
                    tone = PluginDataTone.NEGATIVE,
                ),
                PluginMetric(
                    label = incomeLabel,
                    value = formatMinor(incomeMinor),
                    tone = PluginDataTone.POSITIVE,
                ),
                PluginMetric(
                    label = "结余",
                    value = formatSignedMinor(incomeMinor - expenseMinor),
                ),
            ),
            periodLabel = formatPeriodLabel(mode, anchor),
            emptyTitle = "当前周期还没有记录",
            emptyMessage = "记一笔支出或收入。",
        )
    }

    private fun loadInsights(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val mode = parsePeriodMode(context.request.params["period"])
        val anchor = parseAnchor(context.request.params["anchor"])
        val entries = readAllEntries(storage)
        val current = entriesInPeriod(entries, mode, anchor)
        val previous = entriesInPeriod(entries, mode, previousAnchor(mode, anchor))
        val expenseEntries = current.filter { it.direction == "expense" }
        val currentExpense = expenseEntries.sumOf { it.amountMinor }
        val previousExpense = previous.filter { it.direction == "expense" }.sumOf { it.amountMinor }

        val comparisonLabel = when (mode) {
            "day" -> "较昨日"
            "month" -> "较上月"
            else -> "较上年"
        }
        val comparison = if (previousExpense > 0L) {
            val pct = (currentExpense.toDouble() - previousExpense) * 100.0 / previousExpense
            PluginMetric(
                label = comparisonLabel,
                value = String.format(Locale.US, "%+.1f%%", pct),
                tone = when {
                    currentExpense > previousExpense -> PluginDataTone.NEGATIVE
                    currentExpense < previousExpense -> PluginDataTone.POSITIVE
                    else -> PluginDataTone.NEUTRAL
                },
            )
        } else {
            PluginMetric(label = comparisonLabel, value = "—")
        }

        val average = when (mode) {
            "day" -> {
                val value = if (expenseEntries.isEmpty()) {
                    "—"
                } else {
                    formatMinor(expenseEntries.sumOf { it.amountMinor } / expenseEntries.size)
                }
                PluginMetric(label = "单笔平均", value = value)
            }

            "month" -> {
                val value = if (expenseEntries.isEmpty()) {
                    "—"
                } else {
                    formatMinor(currentExpense / YearMonth.from(anchor).lengthOfMonth())
                }
                PluginMetric(label = "日均支出", value = value)
            }

            else -> {
                val value = if (expenseEntries.isEmpty()) {
                    "—"
                } else {
                    formatMinor(currentExpense / 12)
                }
                PluginMetric(label = "月均支出", value = value)
            }
        }

        val topCategory = expenseEntries
            .groupBy { it.category }
            .mapValues { (_, items) -> items.sumOf { it.amountMinor } }
            .maxByOrNull { it.value }
        val largest = expenseEntries.maxByOrNull { it.amountMinor }

        return PluginDataResult(
            metrics = listOf(
                comparison,
                average,
                PluginMetric(
                    label = "最大单笔",
                    value = largest?.let { "${it.category} ${formatMinor(it.amountMinor)}" } ?: "—",
                ),
                PluginMetric(
                    label = "Top 分类",
                    value = topCategory?.let { (category, amount) ->
                        "$category ${formatMinor(amount)}"
                    } ?: "—",
                ),
            ),
            emptyTitle = "暂无支出数据",
            emptyMessage = "记录支出后这里会显示月度洞察。",
        )
    }

    private fun loadBudget(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val mode = parsePeriodMode(context.request.params["period"])
        val anchor = parseAnchor(context.request.params["anchor"])
        val entries = readAllEntries(storage)
        val periodEntries = entriesInPeriod(entries, mode, anchor)
        val expenseMinor = periodEntries
            .filter { it.direction == "expense" }
            .sumOf { it.amountMinor }
        val record = readBudgetRecord(storage)
        val month = YearMonth.from(anchor)
        val monthBudget = budgetForMonth(record, month)
        val carryMinor = if (record != null) {
            budgetCarryMinor(entries, record, anchor)
        } else {
            0L
        }
        val availableMinor = monthBudget + carryMinor
        val metrics = if (monthBudget <= 0L) {
            listOf(PluginMetric(label = "本月预算", value = "未设置"))
        } else {
            val remaining = availableMinor - expenseMinor
            val today = LocalDate.now()
            val dailyAvailable = if (
                mode == "month" && YearMonth.from(anchor) == YearMonth.from(today)
            ) {
                val daysLeft = (today.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
                remaining.coerceAtLeast(0L) / daysLeft
            } else {
                null
            }
            buildList {
                add(
                    PluginMetric(
                        label = "预算剩余",
                        value = formatSignedMinor(remaining),
                        ratio = if (availableMinor > 0L) {
                            (expenseMinor.toFloat() / availableMinor).coerceIn(0f, 1f)
                        } else {
                            1f
                        },
                        tone = if (remaining < 0L) {
                            PluginDataTone.NEGATIVE
                        } else {
                            PluginDataTone.POSITIVE
                        },
                    ),
                )
                dailyAvailable?.let { daily ->
                    add(
                        PluginMetric(
                            label = "今日还可花",
                            value = formatMinor(daily),
                            tone = if (remaining <= 0L) {
                                PluginDataTone.NEGATIVE
                            } else {
                                PluginDataTone.NEUTRAL
                            },
                        ),
                    )
                }
                add(PluginMetric(label = "当月已用", value = formatMinor(expenseMinor)))
                add(PluginMetric(label = "当月预算", value = formatMinor(monthBudget)))
            }
        }

        val carryMode = effectiveCarryMode(record)
        val items = buildList {
            add(
                PluginDataItem(
                    id = "budget-carry",
                    title = "预算结转",
                    subtitle = when (carryMode) {
                        CARRY_POSITIVE ->
                            "上月结余 ${formatSignedMinor(carryMinor)} · 仅结转结余 · 点击切换"

                        CARRY_BOTH ->
                            "上月差额 ${formatSignedMinor(carryMinor)} · 结余与超支都结转 · 点击切换"

                        else -> "各月预算独立 · 点击切换"
                    },
                    trailing = when (carryMode) {
                        CARRY_POSITIVE -> "仅结余"
                        CARRY_BOTH -> "双向"
                        else -> "已关闭"
                    },
                    leadingText = "🔄",
                    groupTitle = "预算设置",
                    tone = when {
                        carryMode == CARRY_OFF -> PluginDataTone.NEUTRAL
                        carryMinor < 0L -> PluginDataTone.NEGATIVE
                        else -> PluginDataTone.POSITIVE
                    },
                    clickAction = PluginAction.Host(
                        actionId = ACTION_SET_BUDGET_CARRY,
                        params = mapOf("mode" to nextCarryMode(carryMode)),
                    ),
                ),
            )
            record?.categoryBudgets.orEmpty()
                .entries
                .sortedBy { it.key }
                .take(MAX_CATEGORY_BUDGETS)
                .forEach { (category, limit) ->
                    val used = periodEntries
                        .filter { it.direction == "expense" && it.category == category }
                        .sumOf { it.amountMinor }
                    val categoryRemaining = limit - used
                    add(
                        PluginDataItem(
                            id = "budget-category-$category",
                            title = category,
                            subtitle = "已用 ${formatMinor(used)} · 预算 ${formatMinor(limit)}",
                            trailing = if (categoryRemaining >= 0L) {
                                "剩余 ${formatMinor(categoryRemaining)}"
                            } else {
                                "超支 ${formatMinor(-categoryRemaining)}"
                            },
                            leadingText = categoryEmoji(category),
                            groupTitle = "分类预算",
                            tone = if (categoryRemaining < 0L) {
                                PluginDataTone.NEGATIVE
                            } else {
                                PluginDataTone.POSITIVE
                            },
                            clickAction = PluginAction.Navigate(
                                PAGE_BUDGET_CATEGORY,
                                mapOf(
                                    "category" to category,
                                    "amount" to plainAmount(limit),
                                ),
                            ),
                        ),
                    )
                }
        }
        return PluginDataResult(
            items = items,
            metrics = metrics,
            periodLabel = formatPeriodLabel(mode, anchor),
            emptyTitle = "未设置预算",
            emptyMessage = "设置每月预算后，这里会显示剩余进度。",
        )
    }

    private fun searchAll(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return PluginDataResult(
                emptyTitle = "输入关键词",
                emptyMessage = "搜索分类或备注。",
            )
        }
        val limit = (context.request.params["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 20)
        val emojiByCategory = categoryEmojiMap(storage)
        val fromSearchPage = context.request.pageId == PAGE_SEARCH
        val items = readAllEntries(storage)
            .filter { entry ->
                entry.category.contains(query, ignoreCase = true) ||
                    entry.note.contains(query, ignoreCase = true) ||
                    entry.counterparty.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<LedgerEntry> { it.occurredOn }
                    .thenByDescending { it.updatedAt },
            )
            .take(limit)
            .map { entry ->
                val returnParams = if (fromSearchPage) {
                    mapOf("returnTo" to "search", "returnQuery" to query)
                } else {
                    emptyMap()
                }
                val params = editParams(entry, returnParams, emojiByCategory)
                PluginDataItem(
                    id = entry.id,
                    title = entry.category,
                    subtitle = entry.occurredOn +
                        entry.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    trailing = formatAmount(entry),
                    leadingText = emojiByCategory[entry.category] ?: categoryEmoji(entry.category),
                    tone = if (entry.direction == "income") {
                        PluginDataTone.POSITIVE
                    } else {
                        PluginDataTone.NEGATIVE
                    },
                    titleMaxLines = 1,
                    subtitleMaxLines = 1,
                    clickAction = PluginAction.Navigate(PAGE_EDIT, params),
                    actions = listOf(
                        PluginDataAction(
                            label = "编辑",
                            action = PluginAction.Navigate(PAGE_EDIT, params),
                        ),
                    ),
                )
            }
        return PluginDataResult(
            items = items,
            emptyTitle = "没有匹配的记录",
            emptyMessage = "尝试更换关键词。",
        )
    }

    private fun readAllEntries(storage: PluginStorage): List<LedgerEntry> =
        storage.readAllRecords(COLLECTION).mapNotNull { record ->
            runCatching { json.decodeFromString<LedgerEntry>(record.payload) }.getOrNull()
        }

    private fun readCategoryRegistry(storage: PluginStorage): LedgerCategoryRegistry {
        val record = storage.get(CATEGORY_COLLECTION, CATEGORY_ID) ?: return LedgerCategoryRegistry()
        return runCatching {
            json.decodeFromString<LedgerCategoryRegistry>(record.payload)
        }.getOrDefault(LedgerCategoryRegistry())
    }

    private fun writeCategoryRegistry(storage: PluginStorage, registry: LedgerCategoryRegistry) {
        val payload = json.encodeToString(registry)
        if (storage.get(CATEGORY_COLLECTION, CATEGORY_ID) == null) {
            storage.insert(CATEGORY_COLLECTION, payload, CATEGORY_ID)
        } else {
            storage.update(CATEGORY_COLLECTION, CATEGORY_ID, payload)
        }
    }

    /** 默认分类与用户自定义分类的 Emoji；自定义设置优先。 */
    private fun categoryEmojiMap(storage: PluginStorage): Map<String, String> =
        DEFAULT_CATEGORY_EMOJIS + readCategoryRegistry(storage).emojiByCategory

    /** 保存记录时顺带记住分类图标；未选图标时保留已有图标或使用默认值。 */
    private fun rememberCategory(storage: PluginStorage, category: String, emoji: String) {
        val registry = readCategoryRegistry(storage)
        val resolved = emoji.ifBlank {
            registry.emojiByCategory[category]
                ?: DEFAULT_CATEGORY_EMOJIS[category]
                ?: categoryEmoji(category)
        }
        if (emoji.isBlank() && category in DEFAULT_CATEGORY_EMOJIS) return
        if (registry.emojiByCategory[category] == resolved) return
        writeCategoryRegistry(
            storage,
            registry.copy(
                emojiByCategory = registry.emojiByCategory + (category to resolved),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun loadCategories(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val registry = readCategoryRegistry(storage)
        val entries = readAllEntries(storage)
        val usage = entries.groupingBy { it.category }.eachCount()
        val defaultOrder = DEFAULT_CATEGORY_EMOJIS.keys
            .withIndex()
            .associate { it.value to it.index }
        val names = (DEFAULT_CATEGORY_EMOJIS.keys +
            registry.emojiByCategory.keys +
            entries.map { it.category })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(
                compareByDescending<String> { usage[it] ?: 0 }
                    .thenBy { defaultOrder[it] ?: Int.MAX_VALUE }
                    .thenBy { it },
            )
        val emojiByCategory = DEFAULT_CATEGORY_EMOJIS + registry.emojiByCategory
        val items = names.take(MAX_CATEGORY_OPTIONS).map { name ->
            val emoji = emojiByCategory[name] ?: categoryEmoji(name)
            PluginDataItem(
                id = "category-$name",
                title = name,
                subtitle = "已记 ${usage[name] ?: 0} 笔",
                leadingText = emoji,
                clickAction = PluginAction.Navigate(
                    PAGE_EDIT,
                    mapOf("category" to name, "categoryEmoji" to emoji),
                ),
            )
        } + PluginDataItem(
            id = "category-new",
            title = "新建类型",
            subtitle = "填写类型名并选择图标",
            leadingText = "➕",
            clickAction = PluginAction.Navigate(PAGE_CATEGORY_NEW, emptyMap()),
        )
        return PluginDataResult(
            items = items,
            emptyTitle = "暂无分类",
            emptyMessage = "返回记账页直接输入分类名称即可创建新分类。",
        )
    }

    private fun createCategory(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val name = context.request.params["name"]?.trim().orEmpty()
        if (name.isBlank()) return PluginActionResult.Failure("类型名称不能为空")
        if (name.length > 40) return PluginActionResult.Failure("类型名称最多 40 个字")
        val icon = context.request.params["icon"]?.trim().orEmpty()
        if (icon.length > 8) return PluginActionResult.Failure("类型图标不合法")
        val registry = readCategoryRegistry(storage)
        val resolved = icon.ifBlank {
            registry.emojiByCategory[name]
                ?: DEFAULT_CATEGORY_EMOJIS[name]
                ?: categoryEmoji(name)
        }
        writeCategoryRegistry(
            storage,
            registry.copy(
                emojiByCategory = registry.emojiByCategory + (name to resolved),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val draft = context.request.params.filterKeys { it != "name" && it != "icon" }
        return PluginActionResult.Navigate(
            PAGE_EDIT,
            draft + mapOf("category" to name, "categoryEmoji" to resolved),
        )
    }

    private data class LoanSummary(
        val loan: LedgerEntry,
        val paidMinor: Long,
        val remainingMinor: Long,
    )

    private fun loanSummaries(entries: List<LedgerEntry>): List<LoanSummary> {
        val loanEntries = entries.filter {
            it.category == LOAN_CATEGORY && it.loanKind.isNotBlank()
        }
        val loanIds = loanEntries.map { it.id }.toSet()
        val repaymentsByLoan = entries
            .filter { it.linkId.isNotBlank() && it.linkId in loanIds }
            .groupBy { it.linkId }
        return loanEntries.map { loan ->
            val paid = repaymentsByLoan[loan.id].orEmpty().sumOf { it.amountMinor }
            LoanSummary(
                loan = loan,
                paidMinor = paid,
                remainingMinor = (loan.amountMinor - paid).coerceAtLeast(0L),
            )
        }
    }

    private fun loadLoans(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val entries = readAllEntries(storage)
        val emojiByCategory = categoryEmojiMap(storage)
        val summaries = loanSummaries(entries)
        val loanIds = summaries.map { it.loan.id }.toSet()
        val outstandingLend = summaries
            .filter { it.loan.loanKind == LOAN_LEND && it.remainingMinor > 0L }
        val outstandingBorrow = summaries
            .filter { it.loan.loanKind == LOAN_BORROW && it.remainingMinor > 0L }
        val settled = summaries.filter { it.remainingMinor <= 0L }
        val unlinkedRepayments = entries.filter { entry ->
            entry.category == REPAY_CATEGORY &&
                (entry.linkId.isBlank() || entry.linkId !in loanIds)
        }

        val items = buildList {
            fun addLoans(group: String, list: List<LoanSummary>, lend: Boolean) {
                val total = list.sumOf { it.remainingMinor }
                list.sortedByDescending { it.loan.occurredOn }
                    .forEachIndexed { index, summary ->
                        add(
                            loanDataItem(
                                summary = summary,
                                groupTitle = group,
                                groupTotal = if (index == 0) formatMinor(total) else "",
                                emojiByCategory = emojiByCategory,
                                lend = lend,
                            ),
                        )
                    }
            }
            addLoans("待收回", outstandingLend, true)
            addLoans("待归还", outstandingBorrow, false)
            unlinkedRepayments
                .sortedWith(
                    compareByDescending<LedgerEntry> { it.occurredOn }
                        .thenByDescending { it.updatedAt },
                )
                .forEach { entry ->
                    add(unlinkedRepaymentItem(entry, emojiByCategory))
                }
            settled.sortedByDescending { it.loan.occurredOn }.forEachIndexed { index, summary ->
                add(
                    loanDataItem(
                        summary = summary,
                        groupTitle = "已结清",
                        groupTotal = if (index == 0) "${settled.size} 笔" else "",
                        emojiByCategory = emojiByCategory,
                        lend = summary.loan.loanKind == LOAN_LEND,
                    ),
                )
            }
        }.take(PluginDataLimits.MAX_ITEMS)

        return PluginDataResult(
            items = items,
            metrics = listOf(
                PluginMetric(
                    label = "待收回",
                    value = formatMinor(outstandingLend.sumOf { it.remainingMinor }),
                    tone = PluginDataTone.POSITIVE,
                ),
                PluginMetric(
                    label = "待归还",
                    value = formatMinor(outstandingBorrow.sumOf { it.remainingMinor }),
                    tone = PluginDataTone.NEGATIVE,
                ),
                PluginMetric(
                    label = "未结清",
                    value = "${outstandingLend.size + outstandingBorrow.size} 笔",
                ),
            ),
            emptyTitle = "还没有借还记录",
            emptyMessage = "用上方按钮记录一笔借出或借入，还款会自动冲减余额。",
        )
    }

    private fun loanDataItem(
        summary: LoanSummary,
        groupTitle: String,
        groupTotal: String,
        emojiByCategory: Map<String, String>,
        lend: Boolean,
    ): PluginDataItem {
        val loan = summary.loan
        val name = loan.counterparty.ifBlank { "未填写对方" }
        val title = if (lend) "借给 $name" else "向 $name 借"
        val subtitle = buildString {
            append(loan.occurredOn)
            append(" · ")
            append(if (lend) "借出 " else "借入 ")
            append(formatMinor(loan.amountMinor))
            if (summary.paidMinor > 0L) {
                append(" · 已")
                append(if (lend) "收 " else "还 ")
                append(formatMinor(summary.paidMinor))
            }
            if (summary.remainingMinor > 0L) {
                append(" · 剩余 ")
                append(formatMinor(summary.remainingMinor))
            }
        }
        val outstanding = summary.remainingMinor > 0L
        return PluginDataItem(
            id = loan.id,
            title = title,
            subtitle = subtitle,
            trailing = if (outstanding) {
                (if (lend) "待收 " else "待还 ") + formatMinor(summary.remainingMinor)
            } else {
                "已结清"
            },
            leadingText = if (lend) "💸" else "📥",
            groupTitle = groupTitle,
            groupTotal = groupTotal,
            tone = when {
                !outstanding -> PluginDataTone.NEUTRAL
                lend -> PluginDataTone.POSITIVE
                else -> PluginDataTone.NEGATIVE
            },
            clickAction = PluginAction.Navigate(
                PAGE_EDIT,
                editParams(loan, emptyMap(), emojiByCategory),
            ),
            actions = if (outstanding) {
                listOf(
                    PluginDataAction(
                        label = if (lend) "记收款" else "记还款",
                        action = PluginAction.Navigate(
                            PAGE_EDIT,
                            repaymentPrefill(loan, summary.remainingMinor),
                        ),
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    private fun unlinkedRepaymentItem(
        entry: LedgerEntry,
        emojiByCategory: Map<String, String>,
    ): PluginDataItem = PluginDataItem(
        id = entry.id,
        title = REPAY_CATEGORY,
        subtitle = entry.occurredOn + " · " +
            (entry.note.ifBlank { "未关联借款" }),
        trailing = formatAmount(entry),
        leadingText = emojiByCategory[REPAY_CATEGORY] ?: "🔁",
        groupTitle = "未关联的还款",
        tone = if (entry.direction == "income") {
            PluginDataTone.POSITIVE
        } else {
            PluginDataTone.NEGATIVE
        },
        clickAction = PluginAction.Navigate(
            PAGE_EDIT,
            editParams(entry, emptyMap(), emojiByCategory),
        ),
        actions = listOf(
            PluginDataAction(
                label = "关联借款",
                action = PluginAction.Navigate(
                    PAGE_LOAN_MATCH,
                    mapOf("repaymentId" to entry.id),
                ),
            ),
        ),
    )

    private fun repaymentPrefill(loan: LedgerEntry, remainingMinor: Long): Map<String, String> =
        buildMap {
            put("amount", plainAmount(remainingMinor))
            put("direction", if (loan.loanKind == LOAN_LEND) "收入" else "支出")
            put("category", REPAY_CATEGORY)
            put("categoryEmoji", "🔁")
            put("loanKind", loan.loanKind)
            put("counterparty", loan.counterparty)
            put("linkId", loan.id)
            put("date", LocalDate.now().toString())
            put("returnTo", "loans")
        }

    private fun loadLoanOptions(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val repaymentId = context.request.params["repaymentId"]?.trim().orEmpty()
        if (repaymentId.isBlank()) {
            return PluginDataResult(
                emptyTitle = "请从借还台账进入",
                emptyMessage = "在未关联的还款上点“关联借款”后再选择。",
            )
        }
        val items = loanSummaries(readAllEntries(storage))
            .filter { it.remainingMinor > 0L }
            .sortedByDescending { it.loan.occurredOn }
            .map { summary ->
                val loan = summary.loan
                val lend = loan.loanKind == LOAN_LEND
                val name = loan.counterparty.ifBlank { "未填写对方" }
                PluginDataItem(
                    id = loan.id,
                    title = if (lend) "借给 $name" else "向 $name 借",
                    subtitle = "${loan.occurredOn} · " +
                        (if (lend) "借出 " else "借入 ") +
                        formatMinor(loan.amountMinor),
                    trailing = "剩余 " + formatMinor(summary.remainingMinor),
                    leadingText = if (lend) "💸" else "📥",
                    tone = if (lend) PluginDataTone.POSITIVE else PluginDataTone.NEGATIVE,
                    clickAction = PluginAction.Host(
                        ACTION_LINK_REPAYMENT,
                        mapOf("repaymentId" to repaymentId, "loanId" to loan.id),
                    ),
                )
            }
        return PluginDataResult(
            items = items,
            emptyTitle = "没有可关联的借款",
            emptyMessage = "先记录一笔“借钱”，再来关联这笔还款。",
        )
    }

    private fun linkRepayment(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val repaymentId = context.request.params["repaymentId"]?.trim().orEmpty()
        val loanId = context.request.params["loanId"]?.trim().orEmpty()
        if (repaymentId.isBlank() || loanId.isBlank()) {
            return PluginActionResult.Failure("缺少还款或借款记录")
        }
        val repaymentRecord = storage.get(COLLECTION, repaymentId)
            ?: return PluginActionResult.Failure("还款记录不存在")
        val repayment = runCatching {
            json.decodeFromString<LedgerEntry>(repaymentRecord.payload)
        }.getOrNull() ?: return PluginActionResult.Failure("还款记录数据损坏")
        val loanRecord = storage.get(COLLECTION, loanId)
            ?: return PluginActionResult.Failure("借款记录不存在")
        val loan = runCatching {
            json.decodeFromString<LedgerEntry>(loanRecord.payload)
        }.getOrNull() ?: return PluginActionResult.Failure("借款记录数据损坏")
        if (loan.category != LOAN_CATEGORY || loan.loanKind.isBlank()) {
            return PluginActionResult.Failure("只能关联到“借钱”记录")
        }
        val updated = repayment.copy(
            category = REPAY_CATEGORY,
            direction = if (loan.loanKind == LOAN_LEND) "income" else "expense",
            loanKind = loan.loanKind,
            counterparty = loan.counterparty,
            linkId = loan.id,
            updatedAt = System.currentTimeMillis(),
        )
        return if (storage.update(COLLECTION, repaymentId, json.encodeToString(updated))) {
            PluginActionResult.Navigate(PAGE_LOANS)
        } else {
            PluginActionResult.Failure("关联失败，请稍后重试")
        }
    }

    private fun normalizeLoanKind(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            LOAN_LEND, "我借出", "借出" -> LOAN_LEND
            LOAN_BORROW, "我借入", "借入" -> LOAN_BORROW
            else -> ""
        }

    private fun entriesInPeriod(
        entries: List<LedgerEntry>,
        mode: String,
        anchor: LocalDate,
    ): List<LedgerEntry> = entries.filter { entry ->
        val date = runCatching { LocalDate.parse(entry.occurredOn) }.getOrNull()
            ?: return@filter false
        when (mode) {
            "day" -> date == anchor
            "month" -> YearMonth.from(date) == YearMonth.from(anchor)
            else -> date.year == anchor.year
        }
    }

    private fun previousAnchor(mode: String, anchor: LocalDate): LocalDate = when (mode) {
        "day" -> anchor.minusDays(1)
        "month" -> anchor.minusMonths(1)
        else -> anchor.minusYears(1)
    }

    private fun readBudgetRecord(storage: PluginStorage): LedgerBudget? {
        val record = storage.get(BUDGET_COLLECTION, BUDGET_ID) ?: return null
        return runCatching { json.decodeFromString<LedgerBudget>(record.payload) }.getOrNull()
    }

    /** 指定月份的预算；未单独保存时回退到旧版默认预算。 */
    private fun budgetForMonth(budget: LedgerBudget?, month: YearMonth): Long {
        if (budget == null) return 0L
        val key = month.toString()
        if (budget.monthlyBudgets.containsKey(key)) {
            return budget.monthlyBudgets.getValue(key)
        }
        return budget.amountMinor.takeIf { it > 0L } ?: 0L
    }

    private fun writeBudget(storage: PluginStorage, budget: LedgerBudget) {
        val payload = json.encodeToString(budget)
        if (storage.get(BUDGET_COLLECTION, BUDGET_ID) == null) {
            storage.insert(BUDGET_COLLECTION, payload, BUDGET_ID)
        } else {
            storage.update(BUDGET_COLLECTION, BUDGET_ID, payload)
        }
    }

    /** 旧数据只有 carryOver 布尔值，这里统一转换为三档模式。 */
    private fun effectiveCarryMode(budget: LedgerBudget?): String {
        val raw = budget?.carryMode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (raw in CARRY_MODES) return raw
        return if (budget?.carryOver == true) CARRY_POSITIVE else CARRY_OFF
    }

    private fun nextCarryMode(mode: String): String = when (mode) {
        CARRY_OFF -> CARRY_POSITIVE
        CARRY_POSITIVE -> CARRY_BOTH
        else -> CARRY_OFF
    }

    /**
     * 上月结转金额：仅结余模式不为负，双向模式允许超支倒扣；仅在查看当前月时生效。
     */
    private fun budgetCarryMinor(
        entries: List<LedgerEntry>,
        budget: LedgerBudget,
        anchor: LocalDate,
    ): Long {
        val mode = effectiveCarryMode(budget)
        if (mode == CARRY_OFF) return 0L
        if (YearMonth.from(anchor) != YearMonth.from(LocalDate.now())) return 0L
        val previousMonth = YearMonth.from(anchor).minusMonths(1)
        val previousBudget = budgetForMonth(budget, previousMonth)
        if (previousBudget <= 0L) return 0L
        val previousExpense = entriesInPeriod(entries, "month", previousMonth.atDay(1))
            .filter { it.direction == "expense" }
            .sumOf { it.amountMinor }
        val difference = previousBudget - previousExpense
        return if (mode == CARRY_BOTH) difference else difference.coerceAtLeast(0L)
    }

    private fun plainAmount(amountMinor: Long): String =
        BigDecimal.valueOf(amountMinor, 2).setScale(2).toPlainString()

    private fun loadEntries(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
            )
        val mode = parsePeriodMode(context.request.params["period"])
        val anchor = parseAnchor(context.request.params["anchor"])
        purgeExpiredTrash(storage, System.currentTimeMillis())
        // 搜索与方向筛选只属于账本页；从账本跳到统计时会带上页面输入，统计不应被这些参数收窄。
        val listPage = context.request.pageId == PAGE_LIST
        val query = if (listPage) context.request.params["query"]?.trim().orEmpty() else ""
        val directionFilter = if (listPage) {
            normalizeDirection(context.request.params["direction"])
        } else {
            null
        }

        val allEntries = readAllEntries(storage)
        val emojiByCategory = categoryEmojiMap(storage)
        val periodEntries = entriesInPeriod(allEntries, mode, anchor)
        val entries = periodEntries
            .filter { entry ->
                directionFilter == null || entry.direction == directionFilter
            }
            .filter { entry ->
                query.isBlank() ||
                    entry.category.contains(query, ignoreCase = true) ||
                    entry.note.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<LedgerEntry> { it.occurredOn }
                    .thenByDescending { it.updatedAt },
            )

        val periodExpenseMinor = periodEntries
            .filter { it.direction == "expense" }
            .sumOf { it.amountMinor }
        val periodIncomeMinor = periodEntries
            .filter { it.direction == "income" }
            .sumOf { it.amountMinor }
        val expenseMinor = entries.filter { it.direction == "expense" }.sumOf { it.amountMinor }
        val maxMinor = maxOf(periodExpenseMinor, periodIncomeMinor, 1L).toFloat()
        // 顶部摘要只反映所选周期，不随搜索与方向筛选变化，避免“结余”被筛选为 0 造成误解。
        val metrics = buildList {
            add(
                PluginMetric(
                    label = "结余",
                    value = formatSignedMinor(periodIncomeMinor - periodExpenseMinor),
                ),
            )
            add(
                PluginMetric(
                    label = "支出",
                    value = formatMinor(periodExpenseMinor),
                    ratio = (periodExpenseMinor / maxMinor).coerceIn(0f, 1f),
                    tone = PluginDataTone.NEGATIVE,
                ),
            )
            add(
                PluginMetric(
                    label = "收入",
                    value = formatMinor(periodIncomeMinor),
                    ratio = (periodIncomeMinor / maxMinor).coerceIn(0f, 1f),
                    tone = PluginDataTone.POSITIVE,
                ),
            )
            if (context.request.pageId == PAGE_LIST) {
                val budgetRecord = readBudgetRecord(storage)
                val monthBudget = budgetForMonth(budgetRecord, YearMonth.from(anchor))
                if (budgetRecord != null && monthBudget > 0L) {
                    val monthExpense = entriesInPeriod(allEntries, "month", anchor)
                        .filter { it.direction == "expense" }
                        .sumOf { it.amountMinor }
                    val availableMinor = monthBudget +
                        budgetCarryMinor(allEntries, budgetRecord, anchor)
                    val remaining = availableMinor - monthExpense
                    add(
                        PluginMetric(
                            label = "预算剩余",
                            value = formatSignedMinor(remaining),
                            ratio = if (availableMinor > 0L) {
                                (monthExpense.toFloat() / availableMinor).coerceIn(0f, 1f)
                            } else {
                                1f
                            },
                            tone = if (remaining < 0L) {
                                PluginDataTone.NEGATIVE
                            } else {
                                PluginDataTone.POSITIVE
                            },
                        ),
                    )
                }
            }
            if (query.isNotBlank() || directionFilter != null) {
                val filteredIncome = entries
                    .filter { it.direction == "income" }
                    .sumOf { it.amountMinor }
                val value = when (directionFilter) {
                    "income" -> "${entries.size} 笔 · 收入 ${formatMinor(filteredIncome)}"
                    "expense" -> "${entries.size} 笔 · 支出 ${formatMinor(expenseMinor)}"
                    else -> "${entries.size} 笔 · 净额 ${
                        formatSignedMinor(filteredIncome - expenseMinor)
                    }"
                }
                add(PluginMetric(label = "筛选结果", value = value))
            }
        }

        val returnParams = buildMap {
            put("returnPeriod", mode)
            put("returnAnchor", anchor.toString())
            if (query.isNotBlank()) put("returnQuery", query)
            directionFilter?.let { put("returnDirection", it) }
        }
        val items = entries.groupBy { it.occurredOn }
            .flatMap { (date, dayEntries) ->
                val dayNet = dayEntries.sumOf { entry ->
                    if (entry.direction == "income") entry.amountMinor else -entry.amountMinor
                }
                val groupTitle = formatGroupDate(date)
                val groupTotal = formatSignedMinor(dayNet)
                dayEntries.mapIndexed { index, entry ->
                    toDataItem(
                        entry = entry,
                        groupTitle = groupTitle,
                        groupTotal = if (index == 0) groupTotal else "",
                        returnParams = returnParams,
                        emojiByCategory = emojiByCategory,
                    )
                }
            }
            .take(PluginDataLimits.MAX_ITEMS)

        val periodLabel = formatPeriodLabel(mode, anchor)
        return PluginDataResult(
            items = items,
            metrics = metrics,
            bars = buildBars(mode, anchor, entries),
            segments = buildSegments(entries, expenseMinor),
            chartTotal = formatMinor(expenseMinor),
            periodLabel = periodLabel,
            emptyTitle = when {
                query.isNotBlank() -> "没有匹配的记录"
                directionFilter != null -> "当前方向下没有记录"
                mode == "day" -> "该日期没有记录"
                mode == "month" -> "${anchor.monthValue}月没有记录"
                else -> "${anchor.year}年没有记录"
            },
            emptyMessage = when {
                query.isNotBlank() -> "尝试更换关键词，或清空搜索。"
                directionFilter != null -> "切换到“全部”查看所有记录。"
                else -> "切换周期或新增一条记账。"
            },
        )
    }

    private fun buildBars(
        mode: String,
        anchor: LocalDate,
        entries: List<LedgerEntry>,
    ): List<PluginBar> {
        val expenses = entries.filter { it.direction == "expense" }
        if (expenses.isEmpty()) return emptyList()

        val grouped: Map<String, Long> = when (mode) {
            "day" -> expenses.groupBy { it.category }
                .mapValues { (_, items) -> items.sumOf { it.amountMinor } }
                .entries
                .sortedByDescending { it.value }
                .take(10)
                .associate { it.key to it.value }

            "month" -> {
                val month = YearMonth.from(anchor)
                (1..month.lengthOfMonth()).associate { day ->
                    day.toString() to expenses
                        .filter { entry ->
                            runCatching { LocalDate.parse(entry.occurredOn).dayOfMonth }
                                .getOrNull() == day
                        }
                        .sumOf { it.amountMinor }
                }
            }

            else -> (1..12).associate { month ->
                "${month}月" to expenses
                    .filter { entry ->
                        runCatching { LocalDate.parse(entry.occurredOn).monthValue }
                            .getOrNull() == month
                    }
                    .sumOf { it.amountMinor }
            }
        }

        val max = grouped.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        return grouped.map { (label, amount) ->
            PluginBar(
                label = label,
                value = formatMinor(amount),
                ratio = (amount.toFloat() / max.toFloat()).coerceIn(0f, 1f),
            )
        }
    }

    private fun buildSegments(
        entries: List<LedgerEntry>,
        expenseMinor: Long,
    ): List<PluginSegment> {
        if (expenseMinor <= 0L) return emptyList()

        val totals = entries.filter { it.direction == "expense" }
            .groupBy { it.category }
            .mapValues { (_, items) -> items.sumOf { it.amountMinor } }
            .entries
            .sortedByDescending { it.value }

        val top = totals.take(PluginDataLimits.MAX_SEGMENTS - 1)
        val restTotal = totals.drop(PluginDataLimits.MAX_SEGMENTS - 1).sumOf { it.value }
        val grouped = buildList {
            top.forEach { add(it.key to it.value) }
            if (restTotal > 0L) add("其他" to restTotal)
        }

        return grouped.map { (label, amount) ->
            PluginSegment(
                label = label,
                value = formatMinor(amount),
                ratio = (amount.toFloat() / expenseMinor.toFloat()).coerceIn(0f, 1f),
            )
        }
    }

    private fun formatPeriodLabel(mode: String, anchor: LocalDate): String = when (mode) {
        "day" -> anchor.format(DateTimeFormatter.ofPattern("yyyy年M月d日 E", Locale.CHINA))
        "month" -> anchor.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA))
        else -> "${anchor.year}年"
    }

    private fun parsePeriodMode(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "day", "日" -> "day"
            "month", "月" -> "month"
            "year", "年" -> "year"
            else -> "month"
        }

    private fun parseAnchor(raw: String?): LocalDate =
        raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()

    private fun toDataItem(
        entry: LedgerEntry,
        groupTitle: String,
        groupTotal: String,
        returnParams: Map<String, String>,
        emojiByCategory: Map<String, String>,
    ): PluginDataItem {
        val subtitle = entry.note.ifBlank {
            if (entry.direction == "income") "收入" else "支出"
        }
        val editParams = editParams(entry, returnParams, emojiByCategory)

        return PluginDataItem(
            id = entry.id,
            title = entry.category,
            subtitle = subtitle,
            trailing = formatAmount(entry),
            leadingText = emojiByCategory[entry.category] ?: categoryEmoji(entry.category),
            groupTitle = groupTitle,
            groupTotal = groupTotal,
            tone = if (entry.direction == "income") {
                PluginDataTone.POSITIVE
            } else {
                PluginDataTone.NEGATIVE
            },
            clickAction = PluginAction.Navigate(PAGE_EDIT, editParams),
            swipeLeftAction = PluginDataAction(
                label = "删除",
                action = PluginAction.Host(
                    actionId = ACTION_DELETE,
                    params = mapOf("id" to entry.id),
                ),
            ),
            actions = listOf(
                PluginDataAction(
                    label = "编辑",
                    action = PluginAction.Navigate(PAGE_EDIT, editParams),
                ),
            ),
        )
    }

    private fun formatGroupDate(date: String): String = runCatching {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA))
    }.getOrDefault(date)

    private fun categoryEmoji(category: String): String = when {
        category.contains("借") -> "🤝"
        category.contains("还") -> "🔁"
        category.contains("餐") || category.contains("食") -> "🍜"
        category.contains("交通") -> "🚕"
        category.contains("购物") -> "🛍️"
        category.contains("住") || category.contains("房") -> "🏠"
        category.contains("娱乐") -> "🎮"
        category.contains("医") -> "💊"
        category.contains("工资") || category.contains("薪") -> "💰"
        else -> "📦"
    }

    private fun formatSignedMinor(minor: Long, currency: String = "CNY"): String {
        val sign = if (minor < 0) "-" else "+"
        return sign + formatMinor(kotlin.math.abs(minor), currency)
    }

    private fun editParams(
        entry: LedgerEntry,
        returnParams: Map<String, String>,
        emojiByCategory: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        putAll(returnParams)
        put("id", entry.id)
        put("amount", BigDecimal.valueOf(entry.amountMinor, 2).setScale(2).toPlainString())
        put("direction", if (entry.direction == "income") "收入" else "支出")
        put("category", entry.category)
        put("categoryEmoji", emojiByCategory[entry.category] ?: categoryEmoji(entry.category))
        put("date", entry.occurredOn)
        put("note", entry.note)
        entry.loanKind.takeIf { it.isNotBlank() }?.let { put("loanKind", it) }
        entry.counterparty.takeIf { it.isNotBlank() }?.let { put("counterparty", it) }
        entry.linkId.takeIf { it.isNotBlank() }?.let { put("linkId", it) }
    }

    private fun formatAmount(entry: LedgerEntry): String {
        val symbol = if (entry.currency == "CNY") "¥" else "${entry.currency} "
        val sign = if (entry.direction == "income") "+" else "-"
        return sign + symbol + BigDecimal.valueOf(entry.amountMinor, 2)
            .setScale(2)
            .toPlainString()
    }

    private fun formatMinor(amountMinor: Long, currency: String = "CNY"): String {
        val symbol = if (currency == "CNY") "¥" else "$currency "
        return symbol + BigDecimal.valueOf(amountMinor, 2)
            .setScale(2)
            .toPlainString()
    }

    private fun parseAmount(raw: String?): Long {
        val input = raw?.trim().orEmpty()
        require(input.isNotEmpty()) { "金额不能为空" }
        val amount = try {
            BigDecimal(input)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("金额格式不正确", error)
        }
        require(amount > BigDecimal.ZERO) { "金额必须大于 0" }
        val minor = try {
            amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("金额最多保留两位小数，且不能超过上限", error)
        }
        require(minor in 1..MAX_AMOUNT_MINOR) { "金额超出支持范围" }
        return minor
    }

    private fun parseDate(raw: String?): String {
        val input = raw?.trim().orEmpty()
        require(input.isNotEmpty()) { "日期不能为空" }
        return try {
            LocalDate.parse(input).toString()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("日期格式应为 YYYY-MM-DD", error)
        }
    }

    private fun parseDirection(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "支出", "expense" -> "expense"
            "收入", "income" -> "income"
            else -> throw IllegalArgumentException("收支方向只能填写支出或收入")
        }

    private fun normalizeDirection(value: String?): String? =
        when (value?.trim()?.lowercase(Locale.ROOT)) {
            "支出", "expense" -> "expense"
            "收入", "income" -> "income"
            else -> null
        }

    private fun listParams(params: Map<String, String>): Map<String, String> = buildMap {
        put("period", parsePeriodMode(params["period"]))
        put("anchor", parseAnchor(params["anchor"]).toString())
        params["query"]?.trim()?.takeIf { it.isNotBlank() }?.let { put("query", it) }
        normalizeDirection(params["direction"])?.let { put("direction", it) }
    }

    private const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
}
