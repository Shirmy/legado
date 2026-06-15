package io.legado.app.ui.replace

import android.app.Application
import android.text.TextUtils
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.splitNotBlank

/**
 * 替换规则数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class ReplaceRuleViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg rule: ReplaceRule) {
        execute {
            appDb.replaceRuleDao.update(*rule)
        }
    }

    fun delete(rule: ReplaceRule) {
        execute {
            appDb.replaceRuleDao.delete(rule)
        }
    }

    fun toTop(rule: ReplaceRule) {
        execute {
            moveRules(setOf(rule.id), toTop = true)
        }
    }

    fun topSelect(rules: List<ReplaceRule>) {
        execute {
            moveRules(rules.map { it.id }.toSet(), toTop = true)
        }
    }

    fun toBottom(rule: ReplaceRule) {
        execute {
            moveRules(setOf(rule.id), toTop = false)
        }
    }

    fun bottomSelect(rules: List<ReplaceRule>) {
        execute {
            moveRules(rules.map { it.id }.toSet(), toTop = false)
        }
    }

    fun upOrder() {
        execute {
            updateOrder(appDb.replaceRuleDao.all)
        }
    }

    fun fixOrderIfNeeded() {
        execute {
            val rules = appDb.replaceRuleDao.all
            if (rules.withIndex().any { (index, rule) -> rule.order != index + 1 }) {
                updateOrder(rules)
            }
        }
    }

    private fun moveRules(ruleIds: Set<Long>, toTop: Boolean) {
        if (ruleIds.isEmpty()) return
        val rules = appDb.replaceRuleDao.all
        val (selectedRules, otherRules) = rules.partition { it.id in ruleIds }
        if (selectedRules.isEmpty()) return
        updateOrder(
            if (toTop) {
                selectedRules + otherRules
            } else {
                otherRules + selectedRules
            }
        )
    }

    private fun updateOrder(rules: List<ReplaceRule>) {
        val changedRules = rules.mapIndexedNotNull { index, rule ->
            val order = index + 1
            if (rule.order != order) {
                rule.order = order
                rule
            } else {
                null
            }
        }
        if (changedRules.isNotEmpty()) {
            appDb.replaceRuleDao.update(*changedRules.toTypedArray())
        }
    }

    fun enableSelection(rules: List<ReplaceRule>) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy(isEnabled = true)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun disableSelection(rules: List<ReplaceRule>) {
        execute {
            val array = Array(rules.size) {
                rules[it].copy(isEnabled = false)
            }
            appDb.replaceRuleDao.update(*array)
        }
    }

    fun delSelection(rules: List<ReplaceRule>) {
        execute {
            appDb.replaceRuleDao.delete(*rules.toTypedArray())
        }
    }

    fun addGroup(group: String) {
        execute {
            val sources = appDb.replaceRuleDao.noGroup
            sources.forEach { source ->
                source.group = group
            }
            appDb.replaceRuleDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val sources = appDb.replaceRuleDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.group?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    source.group = TextUtils.join(",", it)
                }
            }
            appDb.replaceRuleDao.update(*sources.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        execute {
            execute {
                val sources = appDb.replaceRuleDao.getByGroup(group)
                sources.forEach { source ->
                    source.group?.splitNotBlank(",")?.toHashSet()?.let {
                        it.remove(group)
                        source.group = TextUtils.join(",", it)
                    }
                }
                appDb.replaceRuleDao.update(*sources.toTypedArray())
            }
        }
    }
}
