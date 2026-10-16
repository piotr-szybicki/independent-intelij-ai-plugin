package com.github.piotrszybicki.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

class OneTopLevelClassOrObjectPerFile(
    config: Config,
) : Rule(
        config,
        "Reports Kotlin files containing more than one top-level type declaration.",
    ) {
    override fun visitKtFile(file: KtFile) {
        val topLevelTypes = file.declarations.filterIsInstance<KtClassOrObject>()
        topLevelTypes.drop(1).forEach { declaration ->
            report(
                Finding(
                    Entity.from(declaration),
                    "Move ${declaration.name ?: "this declaration"} to its own file.",
                ),
            )
        }
    }
}
