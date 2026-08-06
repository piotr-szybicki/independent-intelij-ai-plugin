package org.jetbrains.plugins.template.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object AnthropicCredentials {

    private val attributes = CredentialAttributes(generateServiceName("Anthropic Chat", "apiKey"))

    var apiKey: String?
        get() = PasswordSafe.instance.getPassword(attributes)
        set(value) {
            PasswordSafe.instance.set(attributes, value?.takeIf { it.isNotBlank() }?.let { Credentials("apiKey", it) })
        }
}
