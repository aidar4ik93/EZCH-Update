package com.example.homeezch.ai

/** Validate the entire proposal before the first effect, regardless of its source. */
object PlanPreflight {
    fun validate(resolution: Resolution): Resolution {
        if (resolution !is Resolution.Plan) return resolution
        val actions = resolution.actions.toList()
        if (actions.any { it == Action.Cancel }) return Resolution.Plan(listOf(Action.Cancel))
        if (actions.any { it == Action.Dismiss }) return Resolution.Plan(listOf(Action.Dismiss))
        if (actions.isEmpty() || actions.size > 5 || actions.any { !LocalActionRegistry.allowed(it) } ||
            actions.sumOf { (it as? Action.Navigate)?.count ?: 1 } > 20)
            return Resolution.Rejected("План содержит недопустимые действия")
        return Resolution.Plan(actions)
    }
}

