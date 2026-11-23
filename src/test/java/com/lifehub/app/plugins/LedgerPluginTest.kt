package com.lifehub.app.plugins

import com.lifehub.app.plugin.runtime.dataSourceKey

import com.lifehub.app.plugins.testing.FakePluginStorage
import com.lifehub.pluginapi.PluginAction
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionRequest
import com.lifehub.pluginapi.PluginActionResult
import com.lifehub.pluginapi.PluginDataResult
import com.lifehub.pluginapi.PluginDataTone
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class LedgerPluginTest {

    private val storage = FakePluginStorage()

    @Test
    fun savesValidExpenseAndListsIt() {
        val result = handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "12.34",
                "direction" to "支出",
                "category" to "餐饮",
                "date" to "2026-11-11",
                "note" to "午餐",
            ),
        )

        assertTrue(result is PluginActionResult.Navigate)
        assertEquals(1, storage.count("entries"))

        val items = loadItems()
        assertEquals(1, items.items.size)
        val item = items.items.single()
        assertEquals("餐饮", item.title)
        assertEquals("-¥12.34", item.trailing)
        assertEquals("-¥12.34", items.metrics.first().value)
        assertEquals("结余", items.metrics.first().label)
        assertEquals(1f, items.metrics.first { it.label == "支出" }.ratio ?: 0f)
        assertEquals(
            PluginDataTone.NEGATIVE,
            items.metrics.first { it.label == "支出" }.tone,
        )
        assertEquals(
            PluginDataTone.POSITIVE,
            items.metrics.first { it.label == "收入" }.tone,
        )
        assertEquals(PluginDataTone.NEGATIVE, item.tone)
        assertTrue(item.leadingText.isNotBlank())
        assertTrue(item.groupTitle.isNotBlank())
        assertEquals(1, item.actions.size)
        assertEquals("删除", item.swipeLeftAction?.label)

        assertEquals("¥12.34", items.chartTotal)
        val segment = items.segments.single()
        assertEquals("餐饮", segment.label)
        assertEquals("¥12.34", segment.value)
        assertEquals(1f, segment.ratio)
    }

    @Test
    fun rejectsAmountWithMoreThanTwoDecimals() {
        val result = handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "1.234",
                "direction" to "支出",
                "category" to "餐饮",
                "date" to "2026-11-11",
            ),
        )

        assertTrue(result is PluginActionResult.Failure)
        assertTrue((result as PluginActionResult.Failure).message.contains("两位小数"))
        assertEquals(0, storage.count("entries"))
    }

    @Test
    fun rejectsMissingCategory() {
        val result = handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "10",
                "direction" to "收入",
                "date" to "2026-11-11",
            ),
        )

        assertTrue(result is PluginActionResult.Failure)
        assertTrue((result as PluginActionResult.Failure).message.contains("分类"))
    }

    @Test
    fun updatesExistingEntry() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "10",
                "direction" to "支出",
                "category" to "交通",
                "date" to "2026-11-10",
            ),
        )
        val entryId = Json.decodeFromString<LedgerEntry>(
            storage.list("entries").single().payload,
        ).id

        handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "id" to entryId,
                "amount" to "20.50",
                "direction" to "收入",
                "category" to "报销",
                "date" to "2026-11-11",
            ),
        )

        assertEquals(1, storage.count("entries"))
        val updated = Json.decodeFromString<LedgerEntry>(storage.get("entries", entryId)!!.payload)
        assertEquals(2050L, updated.amountMinor)
        assertEquals("income", updated.direction)
    }

    @Test
    fun filtersByDayMonthAndYear() {
        handle(LedgerPlugin.ACTION_SAVE, entryParams("2026-11-10"))
        handle(LedgerPlugin.ACTION_SAVE, entryParams("2026-11-11"))

        val month = loadItems()
        assertEquals(2, month.items.size)
        assertEquals("2026年11月", month.periodLabel)
        assertEquals(30, month.bars.size)
        assertEquals(
            "¥16.00",
            month.metrics.first { it.label == "支出" }.value,
        )

        val day = loadItems(mapOf("period" to "day", "anchor" to "2026-11-11"))
        assertEquals(1, day.items.size)
        assertTrue(day.periodLabel.startsWith("2026年11月11日"))
        assertEquals(1, day.bars.size)
        assertEquals(
            "¥8.00",
            day.metrics.first { it.label == "支出" }.value,
        )
        assertEquals(
            "该日期没有记录",
            loadItems(mapOf("period" to "day", "anchor" to "2026-01-01")).emptyTitle,
        )

        val year = loadItems(mapOf("period" to "year", "anchor" to "2026-11-11"))
        assertEquals("2026年", year.periodLabel)
        assertEquals(12, year.bars.size)
        assertEquals(2, year.items.size)
    }

    @Test
    fun mergesOverflowCategoriesIntoOtherSegment() {
        repeat(9) { index ->
            handle(
                LedgerPlugin.ACTION_SAVE,
                mapOf(
                    "amount" to "1",
                    "direction" to "支出",
                    "category" to "分类$index",
                    "date" to "2026-11-11",
                ),
            )
        }

        val result = loadItems(mapOf("period" to "day", "anchor" to "2026-11-11"))

        assertEquals("¥9.00", result.chartTotal)
        assertEquals(8, result.segments.size)
        assertEquals("其他", result.segments.last().label)
        assertEquals("¥2.00", result.segments.last().value)
        assertEquals(1f, result.segments.sumOf { it.ratio.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun periodAndShiftActionsNavigateWithAnchor() {
        val periodResult = handle(
            LedgerPlugin.ACTION_PERIOD,
            mapOf("period" to "day", "anchor" to "2026-11-11"),
        )
        assertTrue(periodResult is PluginActionResult.Navigate)
        assertEquals(
            mapOf("period" to "day", "anchor" to "2026-11-11"),
            (periodResult as PluginActionResult.Navigate).params,
        )

        val shiftResult = handle(
            LedgerPlugin.ACTION_SHIFT,
            mapOf("period" to "month", "anchor" to "2026-11-11", "delta" to "1"),
        )
        assertTrue(shiftResult is PluginActionResult.Navigate)
        assertEquals(
            mapOf("period" to "month", "anchor" to "2026-12-11"),
            (shiftResult as PluginActionResult.Navigate).params,
        )

        val statsPeriod = handle(
            LedgerPlugin.ACTION_PERIOD,
            mapOf("period" to "year"),
            pageId = "stats",
        )
        assertTrue(statsPeriod is PluginActionResult.Navigate)
        assertEquals(
            "stats",
            (statsPeriod as PluginActionResult.Navigate).pageId,
        )
    }

    @Test
    fun saveAndContinueReturnsToEditWithPresetFields() {
        val today = LocalDate.now().toString()

        val result = handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(today) + mapOf("mode" to "continue"),
        )

        assertTrue(result is PluginActionResult.Navigate)
        result as PluginActionResult.Navigate
        assertEquals("edit", result.pageId)
        assertEquals("支出", result.params["direction"])
        assertEquals("早餐", result.params["category"])
        assertEquals(today, result.params["date"])
        assertEquals(1, storage.count("entries"))
    }

    @Test
    fun searchAndDirectionFilterNarrowEntries() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf("note" to "咖啡"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf(
                "amount" to "100",
                "direction" to "收入",
                "category" to "工资",
            ),
        )

        val expense = loadItems(mapOf("direction" to "expense"))
        assertEquals(1, expense.items.size)
        assertEquals("早餐", expense.items.single().title)
        assertEquals("¥8.00", expense.metrics.first { it.label == "支出" }.value)
        assertEquals(
            "1 笔 · 支出 ¥8.00",
            expense.metrics.first { it.label == "筛选结果" }.value,
        )
        val edit = expense.items.single().actions.first().action as PluginAction.Navigate
        assertEquals("month", edit.params["returnPeriod"])
        assertEquals("2026-11-11", edit.params["returnAnchor"])
        assertEquals("expense", edit.params["returnDirection"])

        val income = loadItems(mapOf("direction" to "income"))
        assertEquals("工资", income.items.single().title)
        // 摘要始终反映整个周期，不随方向筛选变化。
        assertEquals("¥8.00", income.metrics.first { it.label == "支出" }.value)
        assertEquals("¥100.00", income.metrics.first { it.label == "收入" }.value)
        assertEquals(
            "1 笔 · 收入 ¥100.00",
            income.metrics.first { it.label == "筛选结果" }.value,
        )

        val search = loadItems(mapOf("query" to "咖啡"))
        assertEquals(1, search.items.size)
        assertEquals("早餐", search.items.single().title)
        assertEquals(
            "1 笔 · 净额 -¥8.00",
            search.metrics.first { it.label == "筛选结果" }.value,
        )

        val none = loadItems(mapOf("query" to "不存在"))
        assertEquals(0, none.items.size)
        assertTrue(none.emptyTitle.contains("没有匹配"))
    }

    @Test
    fun saveReturnsToListWithOriginViewParams() {
        val result = handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf(
                "returnPeriod" to "year",
                "returnAnchor" to "2026-11-11",
                "returnQuery" to "咖啡",
                "returnDirection" to "expense",
            ),
        )

        assertTrue(result is PluginActionResult.Navigate)
        result as PluginActionResult.Navigate
        assertEquals("list", result.pageId)
        assertEquals("year", result.params["period"])
        assertEquals("expense", result.params["direction"])
        assertEquals("咖啡", result.params["query"])
    }

    @Test
    fun periodChangePreservesSearchAndDirection() {
        val result = handle(
            LedgerPlugin.ACTION_PERIOD,
            mapOf(
                "period" to "year",
                "anchor" to "2026-11-11",
                "query" to "咖啡",
                "direction" to "expense",
            ),
        )

        assertTrue(result is PluginActionResult.Navigate)
        result as PluginActionResult.Navigate
        assertEquals("year", result.params["period"])
        assertEquals("expense", result.params["direction"])
        assertEquals("咖啡", result.params["query"])
    }

    @Test
    fun insightsComputeComparisonAverageLargestAndTopCategory() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf("amount" to "100", "category" to "餐饮"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-12") + mapOf("amount" to "50", "category" to "交通"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-10-11") + mapOf("amount" to "100", "category" to "餐饮"),
        )

        val insights = loadInsights()

        assertEquals("较上月", insights.metrics[0].label)
        assertEquals("+50.0%", insights.metrics[0].value)
        assertEquals(PluginDataTone.NEGATIVE, insights.metrics[0].tone)
        assertEquals("日均支出", insights.metrics[1].label)
        assertEquals("¥5.00", insights.metrics[1].value)
        assertEquals("最大单笔", insights.metrics[2].label)
        assertEquals("餐饮 ¥100.00", insights.metrics[2].value)
        assertEquals("Top 分类", insights.metrics[3].label)
        assertEquals("餐饮 ¥100.00", insights.metrics[3].value)
    }

    @Test
    fun budgetAppearsOnListAndBudgetSource() {
        val saved = handle(
            LedgerPlugin.ACTION_SET_BUDGET,
            mapOf("amount" to "300"),
        )
        assertTrue(saved is PluginActionResult.Refresh)
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(java.time.LocalDate.now().toString()) + mapOf("amount" to "100"),
        )

        val list = loadItems()
        val budgetMetric = list.metrics.first { it.label == "预算剩余" }
        assertEquals("+¥200.00", budgetMetric.value)
        assertEquals(0.333f, budgetMetric.ratio ?: 0f, 0.001f)
        assertEquals(PluginDataTone.POSITIVE, budgetMetric.tone)

        val budget = loadBudget()
        assertEquals("预算剩余", budget.metrics[0].label)
        assertEquals("+¥200.00", budget.metrics[0].value)
        assertEquals("今日还可花", budget.metrics[1].label)
        assertTrue(budget.metrics[1].value.startsWith("¥"))
        assertEquals("当月已用", budget.metrics[2].label)
        assertEquals("¥100.00", budget.metrics[2].value)
        assertEquals("当月预算", budget.metrics[3].label)
        assertEquals("¥300.00", budget.metrics[3].value)

        assertTrue(
            handle(LedgerPlugin.ACTION_CLEAR_BUDGET, emptyMap()) is
                PluginActionResult.Refresh,
        )
        assertEquals("未设置", loadBudget().metrics[0].value)
    }

    @Test
    fun budgetOverSpendUsesNegativeTone() {
        handle(LedgerPlugin.ACTION_SET_BUDGET, mapOf("amount" to "50"))
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf("amount" to "100"),
        )

        val metric = loadBudget().metrics[0]

        assertEquals("-¥50.00", metric.value)
        assertEquals(PluginDataTone.NEGATIVE, metric.tone)
        assertEquals(1f, metric.ratio ?: 0f, 0.001f)
    }

    @Test
    fun barDrilldownNavigatesToDayMonthAndCategory() {
        val month = handle(
            LedgerPlugin.ACTION_DRILLDOWN,
            mapOf(
                "period" to "month",
                "anchor" to "2026-11-11",
                "barLabel" to "14",
            ),
            pageId = "stats",
        )
        assertTrue(month is PluginActionResult.Navigate)
        month as PluginActionResult.Navigate
        assertEquals("list", month.pageId)
        assertEquals("day", month.params["period"])
        assertEquals("2026-11-14", month.params["anchor"])

        val year = handle(
            LedgerPlugin.ACTION_DRILLDOWN,
            mapOf(
                "period" to "year",
                "anchor" to "2026-11-11",
                "barLabel" to "3月",
            ),
            pageId = "stats",
        )
        assertTrue(year is PluginActionResult.Navigate)
        year as PluginActionResult.Navigate
        assertEquals("month", year.params["period"])
        assertEquals("2026-03-01", year.params["anchor"])

        val day = handle(
            LedgerPlugin.ACTION_DRILLDOWN,
            mapOf(
                "period" to "day",
                "anchor" to "2026-11-11",
                "barLabel" to "餐饮",
            ),
            pageId = "stats",
        )
        assertTrue(day is PluginActionResult.Navigate)
        day as PluginActionResult.Navigate
        assertEquals("day", day.params["period"])
        assertEquals("2026-11-11", day.params["anchor"])
        assertEquals("餐饮", day.params["query"])

        assertTrue(
            handle(
                LedgerPlugin.ACTION_DRILLDOWN,
                mapOf(
                    "period" to "month",
                    "anchor" to "2026-11-11",
                    "barLabel" to "abc",
                ),
                pageId = "stats",
            ) is PluginActionResult.Success,
        )
    }

    @Test
    fun budgetCarryOverAddsPreviousMonthRemaining() {
        val previous = LocalDate.now().minusMonths(1).withDayOfMonth(10).toString()
        handle(
            LedgerPlugin.ACTION_SET_BUDGET,
            mapOf("amount" to "300", "anchor" to previous),
        )
        handle(LedgerPlugin.ACTION_SET_BUDGET, mapOf("amount" to "300"))
        val toggled = handle(
            LedgerPlugin.ACTION_SET_BUDGET_CARRY,
            mapOf("carry" to "on"),
        )
        assertTrue(toggled is PluginActionResult.Refresh)
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(LocalDate.now().toString()) + mapOf("amount" to "100"),
        )

        val budget = loadBudget()
        // 上月预算 300 且无支出，全部结转；本月可用 600，已用 100。
        assertEquals("+¥500.00", budget.metrics[0].value)
        assertEquals("¥300.00", budget.metrics[3].value)
        val carryItem = budget.items.first { it.id == "budget-carry" }
        assertEquals("仅结余", carryItem.trailing)
        assertTrue(carryItem.subtitle.contains("+¥300.00"))
        assertEquals("+¥500.00", loadItems().metrics.first { it.label == "预算剩余" }.value)

        handle(LedgerPlugin.ACTION_SET_BUDGET_CARRY, mapOf("carry" to "off"))
        assertEquals("+¥200.00", loadBudget().metrics[0].value)
    }

    @Test
    fun monthlyBudgetsAreIndependentAndClearOnlySelectedMonth() {
        val current = LocalDate.now()
        val previous = current.minusMonths(1)
        handle(
            LedgerPlugin.ACTION_SET_BUDGET,
            mapOf("amount" to "300", "anchor" to previous.toString()),
        )
        handle(LedgerPlugin.ACTION_SET_BUDGET, mapOf("amount" to "500"))

        assertEquals(
            "¥500.00",
            loadBudget().metrics.first { it.label == "当月预算" }.value,
        )
        assertEquals(
            "¥300.00",
            loadBudget(mapOf("anchor" to previous.toString()))
                .metrics.first { it.label == "当月预算" }.value,
        )

        handle(
            LedgerPlugin.ACTION_CLEAR_BUDGET,
            mapOf("anchor" to current.toString()),
        )
        assertEquals("未设置", loadBudget().metrics[0].value)
        assertEquals(
            "¥300.00",
            loadBudget(mapOf("anchor" to previous.toString()))
                .metrics.first { it.label == "当月预算" }.value,
        )
    }

    @Test
    fun categoryBudgetsShowUsageAndCanBeRemoved() {
        handle(LedgerPlugin.ACTION_SET_BUDGET, mapOf("amount" to "300"))
        handle(
            LedgerPlugin.ACTION_SET_CATEGORY_BUDGET,
            mapOf("category" to "早餐", "amount" to "50"),
        )
        handle(
            LedgerPlugin.ACTION_SET_CATEGORY_BUDGET,
            mapOf("category" to "交通", "amount" to "100"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(LocalDate.now().toString()) + mapOf("amount" to "20"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(LocalDate.now().toString()) + mapOf(
                "amount" to "40",
                "category" to "交通",
            ),
        )

        val budget = loadBudget()
        val breakfast = budget.items.first { it.title == "早餐" }
        assertEquals("已用 ¥20.00 · 预算 ¥50.00", breakfast.subtitle)
        assertEquals("剩余 ¥30.00", breakfast.trailing)
        assertEquals(PluginDataTone.POSITIVE, breakfast.tone)
        val action = breakfast.clickAction as PluginAction.Navigate
        assertEquals("budget-category", action.pageId)
        assertEquals("早餐", action.params["category"])
        assertEquals("50.00", action.params["amount"])

        val traffic = budget.items.first { it.title == "交通" }
        assertEquals("已用 ¥40.00 · 预算 ¥100.00", traffic.subtitle)
        assertEquals("剩余 ¥60.00", traffic.trailing)

        val cleared = handle(
            LedgerPlugin.ACTION_CLEAR_CATEGORY_BUDGET,
            mapOf("category" to "早餐"),
        )
        assertTrue(cleared is PluginActionResult.Navigate)
        assertEquals("budget", (cleared as PluginActionResult.Navigate).pageId)
        assertTrue(loadBudget().items.none { it.title == "早餐" })
    }

    @Test
    fun savingMonthlyBudgetKeepsCategoryBudgetsAndCarryFlag() {
        handle(
            LedgerPlugin.ACTION_SET_CATEGORY_BUDGET,
            mapOf("category" to "餐饮", "amount" to "80"),
        )
        handle(LedgerPlugin.ACTION_SET_BUDGET_CARRY, mapOf("carry" to "on"))
        handle(LedgerPlugin.ACTION_SET_BUDGET, mapOf("amount" to "300"))

        val saved = Json.decodeFromString<LedgerBudget>(
            storage.get("budget", "monthly")!!.payload,
        )
        assertEquals(0L, saved.amountMinor)
        assertEquals(
            30_000L,
            saved.monthlyBudgets[YearMonth.from(LocalDate.now()).toString()],
        )
        assertEquals(8_000L, saved.categoryBudgets["餐饮"])
        assertTrue(saved.carryOver)
    }

    @Test
    fun globalSearchFindsEntriesByCategoryOrNote() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf("note" to "咖啡"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf(
                "amount" to "100",
                "direction" to "收入",
                "category" to "工资",
            ),
        )

        val result = loadSearch("咖啡")

        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("早餐", item.title)
        assertEquals("-¥8.00", item.trailing)
        val action = item.clickAction as PluginAction.Navigate
        assertEquals("edit", action.pageId)
    }

    @Test
    fun searchActionOpensSearchPage() {
        val result = handle(
            LedgerPlugin.ACTION_SEARCH,
            mapOf("query" to "  咖啡  ", "period" to "year", "direction" to "expense"),
        )

        assertTrue(result is PluginActionResult.Navigate)
        result as PluginActionResult.Navigate
        assertEquals("search", result.pageId)
        assertEquals(mapOf("query" to "咖啡"), result.params)
    }

    @Test
    fun blankSearchShowsMessage() {
        val result = handle(LedgerPlugin.ACTION_SEARCH, mapOf("query" to " "))

        assertTrue(result is PluginActionResult.ShowMessage)
        assertTrue((result as PluginActionResult.ShowMessage).message.contains("关键词"))
    }

    @Test
    fun customCategoryRemembersEmojiAndAppearsInPicker() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf(
                "category" to "宠物",
                "categoryEmoji" to "🐶",
            ),
        )

        val item = loadItems().items.single()
        assertEquals("宠物", item.title)
        assertEquals("🐶", item.leadingText)

        val categories = loadCategories()
        val pet = categories.items.first { it.title == "宠物" }
        assertTrue(pet.subtitle.startsWith("已记"))
        assertEquals("🐶", pet.leadingText)
        val action = pet.clickAction as PluginAction.Navigate
        assertEquals("edit", action.pageId)
        assertEquals("宠物", action.params["category"])
        assertEquals("🐶", action.params["categoryEmoji"])
        assertEquals("新建类型", categories.items.last().title)
        assertEquals(
            "category-new",
            (categories.items.last().clickAction as PluginAction.Navigate).pageId,
        )

        val edit = loadItems().items.single().actions.first().action as PluginAction.Navigate
        assertEquals("🐶", edit.params["categoryEmoji"])
    }

    @Test
    fun creatingCategoryReturnsToEditWithDraft() {
        val result = handle(
            LedgerPlugin.ACTION_CREATE_CATEGORY,
            mapOf(
                "name" to "健身",
                "icon" to "🏃",
                "amount" to "12.5",
                "direction" to "支出",
                "category" to "",
                "date" to "2026-11-22",
            ),
        )

        assertTrue(result is PluginActionResult.Navigate)
        result as PluginActionResult.Navigate
        assertEquals("edit", result.pageId)
        assertEquals("健身", result.params["category"])
        assertEquals("🏃", result.params["categoryEmoji"])
        assertEquals("12.5", result.params["amount"])
        assertEquals("2026-11-22", result.params["date"])
        assertFalse(result.params.containsKey("name"))
        assertFalse(result.params.containsKey("icon"))

        val category = loadCategories().items.first { it.title == "健身" }
        assertEquals("🏃", category.leadingText)
    }

    @Test
    fun creatingExistingCategoryUpdatesIcon() {
        handle(
            LedgerPlugin.ACTION_CREATE_CATEGORY,
            mapOf("name" to "健身", "icon" to "🏃"),
        )
        val updated = handle(
            LedgerPlugin.ACTION_CREATE_CATEGORY,
            mapOf("name" to "健身", "icon" to "⚽"),
        )

        assertTrue(updated is PluginActionResult.Navigate)
        assertEquals(
            "⚽",
            (updated as PluginActionResult.Navigate).params["categoryEmoji"],
        )
        assertEquals(
            "⚽",
            loadCategories().items.first { it.title == "健身" }.leadingText,
        )
    }

    @Test
    fun categoryListSortsByUsage() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-10") + mapOf("category" to "餐饮"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-11") + mapOf("category" to "餐饮"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams("2026-11-12") + mapOf("category" to "交通"),
        )

        val items = loadCategories().items
        assertEquals("餐饮", items[0].title)
        assertEquals("交通", items[1].title)
        assertEquals("已记 2 笔", items[0].subtitle)
    }

    @Test
    fun lendingOutForcesExpenseAndTracksRemaining() {
        val saved = handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "100",
                "direction" to "收入",
                "category" to "借钱",
                "loanKind" to "lend",
                "counterparty" to "张三",
                "date" to "2026-11-11",
                "returnTo" to "loans",
            ),
        )
        assertTrue(saved is PluginActionResult.Navigate)
        assertEquals("loans", (saved as PluginActionResult.Navigate).pageId)

        val loan = Json.decodeFromString<LedgerEntry>(storage.list("entries").single().payload)
        // 我借出属于支出，即使页面传入收入也会被纠正。
        assertEquals("expense", loan.direction)
        assertEquals("lend", loan.loanKind)
        assertEquals("张三", loan.counterparty)

        val loans = loadLoans()
        val item = loans.items.single()
        assertEquals("借给 张三", item.title)
        assertEquals("待收 ¥100.00", item.trailing)
        assertEquals("待收回", item.groupTitle)
        assertEquals("¥100.00", loans.metrics.first { it.label == "待收回" }.value)

        val collect = item.actions.first()
        assertEquals("记收款", collect.label)
        val prefill = (collect.action as PluginAction.Navigate).params
        assertEquals("loans", prefill["returnTo"])
        assertEquals("100.00", prefill["amount"])
        assertEquals("收入", prefill["direction"])
        assertEquals("还钱", prefill["category"])
        assertEquals(loan.id, prefill["linkId"])

        handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "40",
                "direction" to "支出",
                "category" to "还钱",
                "date" to "2026-11-20",
                "loanKind" to "lend",
                "counterparty" to "张三",
                "linkId" to loan.id,
                "returnTo" to "loans",
            ),
        )
        val repayment = Json.decodeFromString<LedgerEntry>(
            storage.list("entries").first { it.id != loan.id }.payload,
        )
        // 对方还款属于收入，方向按原借款自动纠正。
        assertEquals("income", repayment.direction)
        assertEquals(loan.id, repayment.linkId)

        val after = loadLoans()
        assertEquals("待收 ¥60.00", after.items.single().trailing)
        assertEquals("¥60.00", after.metrics.first { it.label == "待收回" }.value)
    }

    @Test
    fun borrowingInForcesIncomeAndRepaymentUsesExpense() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "80",
                "direction" to "支出",
                "category" to "借钱",
                "loanKind" to "borrow",
                "counterparty" to "李四",
                "date" to "2026-11-11",
            ),
        )
        val loan = Json.decodeFromString<LedgerEntry>(storage.list("entries").single().payload)
        assertEquals("income", loan.direction)

        val loans = loadLoans()
        val item = loans.items.single()
        assertEquals("向 李四 借", item.title)
        assertEquals("待还 ¥80.00", item.trailing)
        assertEquals("待归还", item.groupTitle)
        val repay = item.actions.first()
        assertEquals("记还款", repay.label)
        assertEquals("支出", (repay.action as PluginAction.Navigate).params["direction"])
    }

    @Test
    fun rejectsLoanWithoutDirectionOrCounterparty() {
        val missingKind = handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "10",
                "direction" to "支出",
                "category" to "借钱",
                "counterparty" to "王五",
                "date" to "2026-11-11",
            ),
        )
        assertTrue(missingKind is PluginActionResult.Failure)
        assertTrue((missingKind as PluginActionResult.Failure).message.contains("借贷方向"))

        val missingName = handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "10",
                "direction" to "支出",
                "category" to "借钱",
                "loanKind" to "lend",
                "date" to "2026-11-11",
            ),
        )
        assertTrue(missingName is PluginActionResult.Failure)
        assertTrue((missingName as PluginActionResult.Failure).message.contains("对方"))
        assertEquals(0, storage.count("entries"))
    }

    @Test
    fun unlinkedRepaymentCanBeMatchedToLoan() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "50",
                "direction" to "支出",
                "category" to "借钱",
                "loanKind" to "lend",
                "counterparty" to "赵六",
                "date" to "2026-11-10",
            ),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            mapOf(
                "amount" to "20",
                "direction" to "收入",
                "category" to "还钱",
                "date" to "2026-11-12",
            ),
        )
        val loan = Json.decodeFromString<LedgerEntry>(
            storage.list("entries").first { it.payload.contains("\"lend\"") }.payload,
        )
        val repayment = Json.decodeFromString<LedgerEntry>(
            storage.list("entries").first { it.payload.contains("\"还钱\"") }.payload,
        )

        val unlinked = loadLoans().items.first { it.id == repayment.id }
        assertEquals("未关联的还款", unlinked.groupTitle)
        val match = unlinked.actions.first()
        assertEquals("关联借款", match.label)
        assertEquals(
            repayment.id,
            (match.action as PluginAction.Navigate).params["repaymentId"],
        )

        val result = handle(
            LedgerPlugin.ACTION_LINK_REPAYMENT,
            mapOf("repaymentId" to repayment.id, "loanId" to loan.id),
        )
        assertTrue(result is PluginActionResult.Navigate)
        assertEquals("loans", (result as PluginActionResult.Navigate).pageId)

        val linked = Json.decodeFromString<LedgerEntry>(
            storage.get("entries", repayment.id)!!.payload,
        )
        assertEquals(loan.id, linked.linkId)
        assertEquals("lend", linked.loanKind)
        assertEquals("income", linked.direction)
        assertEquals("待收 ¥30.00", loadLoans().items.single().trailing)

        // 关联页只列出还有余额的借款。
        val options = loadLoanOptions(repayment.id)
        assertEquals(1, options.items.size)
        assertEquals("剩余 ¥30.00", options.items.single().trailing)
        assertEquals(
            loan.id,
            (options.items.single().clickAction as PluginAction.Host).params["loanId"],
        )
    }

    @Test
    fun deletesEntryAndReturnsRefresh() {
        handle(LedgerPlugin.ACTION_SAVE, entryParams("2026-11-11"))
        val id = Json.decodeFromString<LedgerEntry>(
            storage.list("entries").single().payload,
        ).id

        val result = handle(LedgerPlugin.ACTION_DELETE, mapOf("id" to id))

        assertTrue(result is PluginActionResult.Refresh)
        result as PluginActionResult.Refresh
        assertEquals("撤销", result.undoLabel)
        assertEquals(
            PluginAction.Host(
                LedgerPlugin.ACTION_RESTORE,
                mapOf("id" to id),
            ),
            result.undoAction,
        )
        assertEquals(0, storage.count("entries"))
        assertEquals(1, storage.count("entries_trash"))
    }

    @Test
    fun restoresDeletedEntryFromTrash() {
        handle(LedgerPlugin.ACTION_SAVE, entryParams("2026-11-11"))
        val id = Json.decodeFromString<LedgerEntry>(
            storage.list("entries").single().payload,
        ).id
        handle(LedgerPlugin.ACTION_DELETE, mapOf("id" to id))

        val restored = handle(LedgerPlugin.ACTION_RESTORE, mapOf("id" to id))

        assertTrue(restored is PluginActionResult.Refresh)
        assertEquals(1, storage.count("entries"))
        assertEquals(0, storage.count("entries_trash"))
        assertEquals(1, loadItems().items.size)
    }

    @Test
    fun summaryAggregatesCurrentMonth() {
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(LocalDate.now().toString()) + mapOf("amount" to "12.34"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(LocalDate.now().toString()) + mapOf(
                "amount" to "100",
                "direction" to "收入",
                "category" to "工资",
            ),
        )

        val summary = loadSummary()

        assertEquals("本月支出", summary.metrics[0].label)
        assertEquals("¥12.34", summary.metrics[0].value)
        assertEquals("本月收入", summary.metrics[1].label)
        assertEquals("¥100.00", summary.metrics[1].value)
        assertEquals("+¥87.66", summary.metrics[2].value)
    }

    @Test
    fun bothCarryModeOffsetsPreviousOverspend() {
        val previous = LocalDate.now().minusMonths(1).withDayOfMonth(10).toString()
        handle(
            LedgerPlugin.ACTION_SET_BUDGET,
            mapOf("amount" to "300", "anchor" to previous),
        )
        handle(LedgerPlugin.ACTION_SET_BUDGET, mapOf("amount" to "300"))
        handle(
            LedgerPlugin.ACTION_SET_BUDGET_CARRY,
            mapOf("mode" to "both"),
        )
        handle(
            LedgerPlugin.ACTION_SAVE,
            entryParams(previous) + mapOf("amount" to "400"),
        )

        val budget = loadBudget()
        // 上月预算 300、支出 400，双向结转 -100；本月可用 200。
        assertEquals("+¥200.00", budget.metrics[0].value)
        val carry = budget.items.first { it.id == "budget-carry" }
        assertEquals("双向", carry.trailing)
        assertTrue(carry.subtitle.contains("-¥100.00"))
        assertEquals(PluginDataTone.NEGATIVE, carry.tone)

        handle(
            LedgerPlugin.ACTION_SET_BUDGET_CARRY,
            mapOf("mode" to "positive"),
        )
        assertEquals("+¥300.00", loadBudget().metrics[0].value)
    }

    @Test
    fun applyBudgetForwardWritesConsecutiveMonths() {
        val start = LocalDate.now()
        val result = handle(
            LedgerPlugin.ACTION_APPLY_BUDGET_FORWARD,
            mapOf(
                "amount" to "500",
                "months" to "3",
                "anchor" to start.toString(),
            ),
        )

        assertTrue(result is PluginActionResult.Refresh)
        result as PluginActionResult.Refresh
        assertEquals("已应用到 3 个月", result.message)
        assertEquals(listOf("amount", "months"), result.clearInputIds)

        val saved = Json.decodeFromString<LedgerBudget>(
            storage.get("budget", "monthly")!!.payload,
        )
        val month = YearMonth.from(start)
        repeat(3) { offset ->
            assertEquals(
                50_000L,
                saved.monthlyBudgets[month.plusMonths(offset.toLong()).toString()],
            )
        }
        assertTrue(saved.monthlyBudgets[month.plusMonths(3).toString()] == null)

        assertTrue(
            handle(
                LedgerPlugin.ACTION_APPLY_BUDGET_FORWARD,
                mapOf("amount" to "500", "months" to "0"),
            ) is PluginActionResult.Failure,
        )
    }

    private fun entryParams(date: String): Map<String, String> = mapOf(
        "amount" to "8",
        "direction" to "支出",
        "category" to "早餐",
        "date" to date,
    )

    private fun handle(
        actionId: String,
        params: Map<String, String>,
        pageId: String = "edit",
    ): PluginActionResult =
        LedgerPlugin.actionHandlers.getValue(actionId).handle(
            PluginActionContext(
                request = PluginActionRequest(
                    pluginId = LedgerPlugin.PLUGIN_ID,
                    pageId = pageId,
                    actionId = actionId,
                    params = params,
                ),
                storage = storage,
            ),
        )

    private fun loadItems(params: Map<String, String> = emptyMap()) =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_ENTRIES))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "list",
                        sourceId = LedgerPlugin.SOURCE_ENTRIES,
                        params = mapOf("period" to "month", "anchor" to "2026-11-11") + params,
                    ),
                    storage = storage,
                ),
            )

    private fun loadInsights(): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_INSIGHTS))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "stats",
                        sourceId = LedgerPlugin.SOURCE_INSIGHTS,
                        params = mapOf("period" to "month", "anchor" to "2026-11-11"),
                    ),
                    storage = storage,
                ),
            )

    private fun loadBudget(params: Map<String, String> = emptyMap()): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_BUDGET))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "budget",
                        sourceId = LedgerPlugin.SOURCE_BUDGET,
                        params = mapOf("period" to "month", "anchor" to "2026-11-11") +
                            params,
                    ),
                    storage = storage,
                ),
            )

    private fun loadSearch(query: String): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_SEARCH))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "search",
                        sourceId = LedgerPlugin.SOURCE_SEARCH,
                        params = mapOf("query" to query, "limit" to "10"),
                    ),
                    storage = storage,
                ),
            )

    private fun loadCategories(): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_CATEGORIES))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "category-pick",
                        sourceId = LedgerPlugin.SOURCE_CATEGORIES,
                    ),
                    storage = storage,
                ),
            )

    private fun loadLoans(): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_LOANS))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "loans",
                        sourceId = LedgerPlugin.SOURCE_LOANS,
                    ),
                    storage = storage,
                ),
            )

    private fun loadLoanOptions(repaymentId: String): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_LOAN_OPTIONS))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "loan-match",
                        sourceId = LedgerPlugin.SOURCE_LOAN_OPTIONS,
                        params = mapOf("repaymentId" to repaymentId),
                    ),
                    storage = storage,
                ),
            )

    private fun loadSummary(): PluginDataResult =
        LedgerPlugin.dataHandlers
            .getValue(dataSourceKey(LedgerPlugin.PLUGIN_ID, LedgerPlugin.SOURCE_SUMMARY))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = LedgerPlugin.PLUGIN_ID,
                        pageId = "home",
                        sourceId = LedgerPlugin.SOURCE_SUMMARY,
                        params = mapOf(
                            "period" to "month",
                            "anchor" to LocalDate.now().toString(),
                        ),
                    ),
                    storage = storage,
                ),
            )
}
