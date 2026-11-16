package com.parishod.watomatic.activity.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RuleFilterTab {
    ALL,
    ACTIVE,
    INACTIVE
}

data class RuleDashboardUiState(
    val totalRules: Int = 0,
    val activeRules: Int = 0,
    val inactiveRules: Int = 0,
    val displayedRules: List<AutoReplyRule> = emptyList(),
    val filterTab: RuleFilterTab = RuleFilterTab.ALL,
    val searchQuery: String = "",
    val availableCategories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val selectedCategoryHasActiveRules: Boolean = false
)

class RuleDashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RuleRepository by lazy {
        val db = AutoReplyRulesDatabase.getInstance(application)
        RuleRepository(db.ruleDao(), db.executionLogDao())
    }

    private val filterTab = MutableStateFlow(RuleFilterTab.ALL)
    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RuleDashboardUiState> = combine(
        repository.allRules,
        filterTab,
        searchQuery,
        selectedCategory
    ) { allRules, tab, query, category ->
        val activeCount = allRules.count { it.isEnabled }
        val inactiveCount = allRules.size - activeCount

        val categories = allRules
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

        val effectiveCategory = category?.takeIf { sel ->
            categories.any { it.equals(sel, ignoreCase = true) }
        }

        val tabFiltered = when (tab) {
            RuleFilterTab.ALL -> allRules
            RuleFilterTab.ACTIVE -> allRules.filter { it.isEnabled }
            RuleFilterTab.INACTIVE -> allRules.filter { !it.isEnabled }
        }

        val categoryFiltered = if (effectiveCategory == null) {
            tabFiltered
        } else {
            tabFiltered.filter { it.category.trim().equals(effectiveCategory, ignoreCase = true) }
        }

        val trimmedQuery = query.trim()
        val finalFiltered = if (trimmedQuery.isEmpty()) {
            categoryFiltered
        } else {
            categoryFiltered.filter { rule ->
                rule.name.contains(trimmedQuery, ignoreCase = true) ||
                    rule.category.contains(trimmedQuery, ignoreCase = true) ||
                    rule.replyMessage.contains(trimmedQuery, ignoreCase = true) ||
                    rule.keywords.any { kw -> kw.contains(trimmedQuery, ignoreCase = true) }
            }
        }

        val selectedCategoryHasActive = effectiveCategory != null && allRules.any {
            it.category.trim().equals(effectiveCategory, ignoreCase = true) && it.isEnabled
        }

        RuleDashboardUiState(
            totalRules = allRules.size,
            activeRules = activeCount,
            inactiveRules = inactiveCount,
            displayedRules = finalFiltered,
            filterTab = tab,
            searchQuery = query,
            availableCategories = categories,
            selectedCategory = effectiveCategory,
            selectedCategoryHasActiveRules = selectedCategoryHasActive
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = RuleDashboardUiState()
    )

    fun setFilterTab(tab: RuleFilterTab) {
        filterTab.value = tab
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSelectedCategory(category: String?) {
        selectedCategory.value = category?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setCategoryRulesEnabled(category: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setCategoryEnabled(category, enabled)
        }
    }

    fun toggleRuleEnabled(rule: AutoReplyRule, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setRuleEnabled(rule.id, enabled)
        }
    }

    fun deleteRule(rule: AutoReplyRule) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteRule(rule)
        }
    }

    fun duplicateRule(rule: AutoReplyRule) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.duplicateRule(rule)
        }
    }

    fun adjustPriority(rule: AutoReplyRule, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val newPriority = (rule.priority + delta).coerceIn(0, 100)
            if (newPriority != rule.priority) {
                repository.updateRulePriority(rule.id, newPriority)
            }
        }
    }
}
