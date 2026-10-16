package com.github.piotrszybicki.detektrules

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class ProjectRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("project-rules")

    override fun instance() =
        RuleSet(
            ruleSetId,
            mapOf(RuleName("OneTopLevelClassOrObjectPerFile") to ::OneTopLevelClassOrObjectPerFile),
        )
}
